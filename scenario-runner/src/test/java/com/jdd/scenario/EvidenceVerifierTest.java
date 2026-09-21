package com.jdd.scenario;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class EvidenceVerifierTest {
    @TempDir Path root;
    static final String BUILD = "123456789abc-123456789abc";
    static final String SOURCE = "commerce-core/src/main/java/Example.java";
    EvidenceVerifier verifier;
    ObjectNode manifest;

    @BeforeEach void prepareArchive() throws Exception {
        Path directory = root.resolve("runtime/evidence/source/" + BUILD);
        Path code = directory.resolve(SOURCE);
        Files.createDirectories(code.getParent()); Files.writeString(code, "first line\nsecond line\n");
        Files.createDirectories(directory.resolve("policy"));
        Files.writeString(directory.resolve("policy/business-policy.md"), "정상 정책\n");
        manifest = Json.object("schemaVersion", "1.0", "buildId", BUILD, "workingTreeDirty", false,
                "commitSha", "123456789abc" + "0".repeat(28), "policyVersion", "demo-v1",
                "files", Json.object(SOURCE, Json.sha(Files.readAllBytes(code))),
                "policy", Json.object("path", "policy/business-policy.md", "version", "demo-v1",
                        "sha256", Json.sha(Files.readAllBytes(directory.resolve("policy/business-policy.md")))));
        Json.write(directory.resolve("manifest.json"), manifest);
        verifier = new EvidenceVerifier(root, BUILD, manifest);
    }
    ObjectNode detail(String type, JsonNode source, JsonNode content) {
        return Json.object("evidenceId", "e1", "type", type, "summary", "actual test observation",
                "observedAt", "2026-09-22T00:00:00Z", "source", source, "content", content, "truncated", false);
    }
    ObjectNode prepared(JsonNode db) { return Json.object("prefix", "case-a", "database", db, "context", Json.object()); }

    @Test void comparesExactDatabaseRowsAndTimestampInstants() throws Exception {
        var original = Json.object("id", "order-a", "status", "PAID", "created_at", "2026-09-22T00:00:00+00:00");
        var observed = Json.object("id", "order-a", "status", "PAID", "created_at", "2026-09-22T00:00:00Z");
        var evidence = detail("DATA", Json.object("schema", "commerce", "table", "orders", "recordIds", List.of("order-a"),
                "queryDescription", "order_id=order-a 상태"), Json.object("columns", List.of("id", "status", "created_at"), "rows", List.of(observed)));
        var input = prepared(Json.object("orders", List.of(original)));
        verifier.verify(evidence, input);
        observed.put("status", "CANCELLED");
        evidence.set("content", Json.object("columns", List.of("id", "status", "created_at"), "rows", List.of(observed)));
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(evidence, input));
    }

    @Test void emptyAndDuplicateRowsCannotHideActualRecords() {
        var row = Json.object("id", "order-a", "status", "PAID");
        var input = prepared(Json.object("orders", List.of(row)));
        var empty = detail("DATA", Json.object("schema", "commerce", "table", "orders", "recordIds", List.of(),
                "queryDescription", "order_id=order-a 状態"), Json.object("columns", List.of("id", "status"), "rows", List.of()));
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(empty, input));
        empty.set("content", Json.object("columns", List.of("id", "status"), "rows", List.of(row, row)));
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(empty, input));
    }

    @Test void aggregateInventoryIsRecalculatedFromActualRows() throws Exception {
        var input = prepared(Json.object("inventory_movements", List.of(
                Json.object("id", "m1", "product_id", "p1", "movement_type", "RESERVE", "quantity_delta", -1),
                Json.object("id", "m2", "product_id", "p1", "movement_type", "RESERVE", "quantity_delta", -1))));
        var values = Json.object("movement_type", "RESERVE", "movement_count", 2, "quantity_delta_total", -2);
        var evidence = detail("DATA", Json.object("schema", "commerce", "table", "inventory_movements", "recordIds", List.of(),
                "queryDescription", "product_id=p1 정확한 전체 집계"), Json.object("columns", List.of("movement_type", "movement_count", "quantity_delta_total"), "rows", List.of(values)));
        verifier.verify(evidence, input);
        values.put("movement_count", 1);
        evidence.set("content", Json.object("columns", List.of("movement_type", "movement_count", "quantity_delta_total"), "rows", List.of(values)));
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(evidence, input));
    }

    @Test void codeAndPolicyMustEqualTheActualArchivedLines() throws Exception {
        var evidence = detail("CODE", Json.object("buildId", BUILD, "path", SOURCE, "startLine", 2, "endLine", 2),
                Json.MAPPER.valueToTree("second line"));
        verifier.verify(evidence, prepared(Json.object()));
        evidence.put("content", "invented source");
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(evidence, prepared(Json.object())));
        Files.writeString(root.resolve("runtime/evidence/source/" + BUILD + "/" + SOURCE), "changed runtime source");
        assertThrows(Json.VerificationFailure.class, () -> new EvidenceVerifier(root, BUILD, manifest));
        assertThrows(Json.VerificationFailure.class, () -> EvidenceVerifier.safe(root, "../outside"));
    }

    @Test void logComparesRawLineAndCaseIdentity() throws Exception {
        Path directory = root.resolve("runtime/evidence/logs/commerce/" + BUILD);
        Files.createDirectories(directory);
        var event = Json.object("buildId", BUILD, "requestId", "case-a-request", "event", "ORDER_CREATED");
        Files.writeString(directory.resolve("business.jsonl"), Json.MAPPER.writeValueAsString(event) + "\n");
        var evidence = detail("LOG", Json.object("buildId", BUILD, "path", "business.jsonl", "startLine", 1, "endLine", 1), Json.MAPPER.valueToTree(List.of(event)));
        verifier.verify(evidence, prepared(Json.object()));
        var foreign = Json.object("prefix", "case-b", "database", Json.object(), "context", Json.object());
        assertThrows(Json.VerificationFailure.class, () -> verifier.verify(evidence, foreign));
    }
}
