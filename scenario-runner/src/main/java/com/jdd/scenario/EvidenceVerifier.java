package com.jdd.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Independent comparisons against the prepared actual PostgreSQL rows and immutable runtime files. */
final class EvidenceVerifier {
    private final Path root;
    private final String build;
    private final JsonNode manifest;
    private static final Set<String> TABLES = Set.of("products", "product_stock", "orders", "order_items",
            "payments", "refunds", "coupons", "customer_coupons", "coupon_usages", "inventory_movements");
    private static final Pattern SELECTOR = Pattern.compile("(?:^|[ ,])((?:o\\.|cc\\.)?(?:id|customer_id|order_id|product_id|checkout_key|request_id))=([^ ,;]+)");

    EvidenceVerifier(Path root, String build, JsonNode manifest) throws IOException {
        this.root = root; this.build = build; this.manifest = manifest;
        Path directory = root.resolve("runtime/evidence/source/" + build);
        Json.require(Json.read(safe(directory, "manifest.json")).equals(manifest), "Prepared source manifest changed");
        Json.require(manifest.path("buildId").asText().equals(build) && manifest.path("files").isObject()
                && !manifest.path("files").isEmpty() && manifest.path("workingTreeDirty").isBoolean()
                && !manifest.path("workingTreeDirty").asBoolean(), "Uncommitted or missing source manifest");
        for (var entry : manifest.path("files").properties()) {
            String name = entry.getKey();
            Json.require(name.startsWith("commerce-app/src/main/java/") || name.startsWith("commerce-core/src/main/java/")
                    || name.startsWith("commerce-infra/src/main/java/")
                    || name.startsWith("commerce-infra/src/main/resources/db/migration/"), "Unexpected source area");
            Json.require(!name.contains("/test/") && !name.contains("/reproduction/"), "Evaluation source in runtime manifest");
            checked(safe(directory, name), entry.getValue().asText());
        }
        Json.require(manifest.path("policy").path("path").asText().equals("policy/business-policy.md")
                && manifest.path("policy").path("version").equals(manifest.path("policyVersion")), "Missing archived policy binding");
        checked(safe(directory, "policy/business-policy.md"), manifest.path("policy").path("sha256").asText());
    }

    void verify(JsonNode detail, JsonNode preparedCase) throws IOException {
        Json.text(detail, "evidenceId"); Json.text(detail, "summary");
        Instant.parse(Json.text(detail, "observedAt"));
        Json.require(detail.path("truncated").isBoolean(), "Evidence must disclose truncation");
        String type = Json.text(detail, "type");
        JsonNode source = detail.path("source");
        switch (type) {
            case "CODE" -> {
                sameBuild(source);
                String path = Json.text(source, "path");
                Json.require(manifest.path("files").has(path), "Code is outside the runtime manifest");
                Path file = safe(root.resolve("runtime/evidence/source/" + build), path);
                checked(file, manifest.path("files").path(path).asText());
                compareLines(file, source, detail.path("content"));
            }
            case "POLICY" -> {
                sameBuild(source);
                Json.require(source.path("path").equals(manifest.path("policy").path("path"))
                        && source.path("version").equals(manifest.path("policy").path("version"))
                        && source.path("sha256").equals(manifest.path("policy").path("sha256")), "Policy binding mismatch");
                compareLines(safe(root.resolve("runtime/evidence/source/" + build), "policy/business-policy.md"), source, detail.path("content"));
            }
            case "LOG" -> {
                sameBuild(source);
                Path file = safe(root.resolve("runtime/evidence/logs/commerce/" + build), Json.text(source, "path"));
                Json.require(file.getFileName().toString().endsWith(".jsonl") && Files.size(file) <= 64L * 1024 * 1024,
                        "Unexpected or oversized log file");
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int start = source.path("startLine").asInt(), end = source.path("endLine").asInt();
                Json.require(start >= 1 && end >= start && end <= lines.size() && end - start < 100,
                        "Invalid log line range");
                JsonNode content = detail.path("content");
                Json.require(content.isArray() && content.size() == end - start + 1, "Log line count mismatch");
                for (int index = start; index <= end; index++) {
                    JsonNode observed = Json.MAPPER.readTree(lines.get(index - 1));
                    Json.require(observed.equals(content.get(index - start)) && observed.path("buildId").asText().equals(build),
                            "Log content differs from the actual JSONL line");
                    Json.require(inScope(observed, preparedCase), "Log does not identify this synthetic case");
                }
            }
            case "DATA" -> verifyData(detail, preparedCase);
            default -> throw new Json.VerificationFailure("Unknown evidence type");
        }
    }

    private void sameBuild(JsonNode source) {
        Json.require(source.path("buildId").asText().equals(build), "Evidence belongs to another build");
    }
    static Path safe(Path directory, String relative) {
        Json.require(!relative.isBlank() && !relative.startsWith("/") && !relative.contains("\\") && !relative.contains(":"), "Invalid evidence path");
        Path path = directory;
        Json.require(!Files.isSymbolicLink(directory), "Evidence directory is a symlink");
        for (String component : relative.split("/", -1)) {
            Json.require(!component.isBlank() && !component.equals(".") && !component.equals(".."), "Evidence path traversal");
            path = path.resolve(component);
            Json.require(!Files.isSymbolicLink(path), "Evidence path follows a symlink");
        }
        Json.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Evidence file is missing");
        return path;
    }
    private static void checked(Path file, String expected) throws IOException {
        Json.require(expected.matches("[0-9a-f]{64}") && Files.size(file) <= 4L * 1024 * 1024,
                "Invalid source checksum or size");
        Json.require(Json.sha(Files.readAllBytes(file)).equals(expected), "Runtime file checksum mismatch");
    }
    private static void compareLines(Path file, JsonNode source, JsonNode content) throws IOException {
        List<String> lines = Files.readString(file, StandardCharsets.UTF_8).lines().toList();
        int start = source.path("startLine").asInt(), end = source.path("endLine").asInt();
        Json.require(start >= 1 && end >= start && end <= lines.size(), "Invalid source line range");
        Json.require(content.isString() && content.asText().equals(String.join("\n", lines.subList(start - 1, end))),
                "Saved source/policy differs from the actual runtime file");
    }
    private static boolean inScope(JsonNode row, JsonNode preparedCase) {
        String prefix = preparedCase.path("prefix").asText();
        if (prefix.isBlank()) prefix = preparedCase.path("preparation").path("prefix").asText();
        for (JsonNode value : row) if (value.isString() && !prefix.isBlank() && value.asText().startsWith(prefix + "-")) return true;
        for (var context : preparedCase.path("context").properties()) {
            if (!context.getKey().equals("occurredAt")) for (JsonNode value : row) if (value.equals(context.getValue())) return true;
        }
        return false;
    }

    private void verifyData(JsonNode detail, JsonNode preparedCase) {
        JsonNode source = detail.path("source"), content = detail.path("content"), database = preparedCase.path("database");
        String table = Json.text(source, "table"), description = Json.text(source, "queryDescription");
        Json.require(source.path("schema").asText().equals("commerce") && TABLES.contains(table), "Unexpected evidence table");
        Json.require(database.path(table).isArray(), "No actual PostgreSQL snapshot for this evidence table");
        var columns = Json.list(content, "columns"); var rows = Json.list(content, "rows");
        var ids = Json.list(source, "recordIds");
        Json.require(columns.size() > 0 && rows.size() <= 100, "Invalid DATA dimensions");
        var selectors = new LinkedHashMap<String, String>();
        var matcher = SELECTOR.matcher(description);
        while (matcher.find()) selectors.put(matcher.group(1).replaceFirst("^o\\.", "")
                .replace("cc.customer_id", "customer_id"), matcher.group(2));
        Json.require(!selectors.isEmpty(), "DATA query has no inspectable selector");
        List<JsonNode> expected = candidates(database, table, selectors, columns);
        var foundIds = new HashSet<String>();
        var matching = new HashSet<Integer>();
        for (JsonNode row : rows) {
            Json.require(row.isObject() && row.size() == columns.size(), "DATA columns/row mismatch");
            int selected = -1;
            for (int i = 0; i < expected.size(); i++) {
                if (matching.contains(i)) continue;
                boolean matches = true;
                for (JsonNode column : columns) {
                    String name = column.asText();
                    if (!row.has(name) || !sameValue(row.get(name), expected.get(i).get(name))) { matches = false; break; }
                }
                if (matches) { selected = i; break; }
            }
            Json.require(selected >= 0, "DATA row differs from the actual PostgreSQL preparation snapshot");
            matching.add(selected);
            if (row.hasNonNull("id")) foundIds.add(row.path("id").asText());
            else if (table.equals("product_stock") && row.hasNonNull("product_id")) foundIds.add(row.path("product_id").asText());
        }
        var declared = new HashSet<String>();
        for (JsonNode id : ids) Json.require(id.isString() && declared.add(id.asText()), "Duplicate/invalid DATA record ID");
        Json.require(foundIds.equals(declared), "DATA record IDs do not match returned records");
        if (!detail.path("truncated").asBoolean()) Json.require(rows.size() == expected.size(), "Untruncated DATA omitted actual matching rows");
    }

    static boolean sameValue(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) return actual == expected;
        if (actual.isNumber() && expected.isNumber()) return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        if (actual.equals(expected)) return true;
        if (actual.isString() && expected.isString()) {
            try { return OffsetDateTime.parse(actual.asText()).toInstant().equals(OffsetDateTime.parse(expected.asText()).toInstant()); }
            catch (java.time.DateTimeException ignored) { return false; }
        }
        return false;
    }

    private static List<JsonNode> candidates(JsonNode db, String table, Map<String, String> selectors, JsonNode columns) {
        var rows = new ArrayList<JsonNode>();
        for (JsonNode original : db.path(table)) {
            ObjectNode row = (ObjectNode) original.deepCopy();
            if (table.equals("order_items")) {
                JsonNode order = find(db.path("orders"), "id", row.path("order_id").asText());
                if (order != null) {
                    row.set("order_status", order.path("status"));
                    for (String field : List.of("request_id", "checkout_key", "created_at")) row.set(field, order.path(field));
                }
            }
            if (matchesSelectors(row, db, table, selectors)) rows.add(row);
        }
        Set<String> names = new HashSet<>(); for (JsonNode column : columns) names.add(column.asText());
        if (names.contains("movement_count") || names.contains("order_count")) {
            String group = names.contains("movement_count") ? "movement_type" : "order_status";
            var aggregate = new LinkedHashMap<String, ObjectNode>();
            var orderIds = new LinkedHashMap<String, Set<String>>();
            for (JsonNode row : rows) {
                String value = row.path(group).asText();
                ObjectNode target = aggregate.computeIfAbsent(value, ignored -> Json.object(group, value));
                if (group.equals("movement_type")) {
                    target.put("movement_count", target.path("movement_count").asLong() + 1);
                    target.put("quantity_delta_total", target.path("quantity_delta_total").asLong() + row.path("quantity_delta").asLong());
                } else {
                    orderIds.computeIfAbsent(value, ignored -> new HashSet<>()).add(row.path("order_id").asText());
                    target.put("order_count", orderIds.get(value).size());
                    target.put("ordered_quantity", target.path("ordered_quantity").asLong() + row.path("quantity").asLong());
                }
            }
            return new ArrayList<>(aggregate.values());
        }
        return rows;
    }
    private static JsonNode find(JsonNode rows, String key, String value) {
        for (JsonNode row : rows) if (row.path(key).asText().equals(value)) return row;
        return null;
    }
    private static boolean matchesSelectors(JsonNode row, JsonNode db, String table, Map<String, String> selectors) {
        for (var selector : selectors.entrySet()) {
            String key = selector.getKey(), value = selector.getValue();
            if (table.equals("coupons")) {
                boolean linked = false;
                for (JsonNode cc : db.path("customer_coupons")) {
                    if (cc.path("coupon_id").equals(row.path("id"))
                            && cc.path(key.equals("cc.id") ? "id" : key).asText().equals(value)) linked = true;
                }
                if (!linked) return false;
            } else if (table.equals("coupon_usages") && (key.equals("customer_id") || key.equals("cc.id"))) {
                JsonNode cc = find(db.path("customer_coupons"), "id", row.path("customer_coupon_id").asText());
                if (cc == null || !cc.path(key.equals("cc.id") ? "id" : key).asText().equals(value)) return false;
            } else if (table.equals("orders") && key.equals("product_id")) {
                boolean linked = false;
                for (JsonNode item : db.path("order_items")) if (item.path("order_id").equals(row.path("id"))
                        && item.path("product_id").asText().equals(value)) linked = true;
                if (!linked) return false;
            } else {
                String field = key.equals("cc.id") ? "id" : key;
                if ((table.equals("orders") && key.equals("order_id")) || (table.equals("products") && key.equals("product_id"))) field = "id";
                if (!row.path(field).asText().equals(value)) return false;
            }
        }
        return true;
    }
}
