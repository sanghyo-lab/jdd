package com.jdd.scenario;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Structural and case-specific coverage checks; no generated text is accepted as a source. */
final class ReportChecks {
    private static final Set<String> MISSING = Set.of("message", "context.customerId", "context.orderId", "context.productId",
            "context.requestId", "context.checkoutKey", "context.occurredAt");
    private static final Map<String, Set<String>> TABLES = Map.of(
            "VOC-01", Set.of("orders", "payments"), "VOC-02", Set.of("coupons", "customer_coupons"),
            "VOC-03", Set.of("orders", "coupons"), "VOC-04", Set.of("orders"),
            "VOC-05", Set.of("orders", "payments", "refunds"),
            "VOC-06", Set.of("orders", "refunds", "customer_coupons", "coupon_usages"),
            "VOC-07", Set.of("product_stock", "inventory_movements", "order_items"),
            "NORMAL", Set.of("orders", "payments"));
    private static final Map<String, Set<String>> EVENTS = Map.of(
            "VOC-01", Set.of("PAYMENT_APPROVED"), "VOC-02", Set.of("COUPON_REJECTED"),
            "VOC-03", Set.of("DISCOUNT_CALCULATED"), "VOC-04", Set.of("ORDER_CREATED"),
            "VOC-05", Set.of("REFUND_FAILED"), "VOC-06", Set.of("REFUND_COMPLETED"),
            "VOC-07", Set.of("INVENTORY_READ", "INVENTORY_RESERVED"),
            "NORMAL", Set.of("PAYMENT_APPROVED"));
    private ReportChecks() {}

    static void verify(String caseId, JsonNode investigation, Map<String, JsonNode> evidence) {
        Json.require(investigation.path("schemaVersion").asText().equals("1.0") && investigation.path("error").isNull(),
                "Invalid successful investigation envelope");
        JsonNode report = investigation.path("report");
        Json.require(report.isObject() && report.path("schemaVersion").asText().equals("1.0"), "Missing report");
        Json.text(report, "summary");
        var itemIds = new HashSet<String>();
        var cited = new HashSet<String>();
        var supported = new HashSet<String>();
        for (String section : List.of("facts", "hypotheses", "actions", "prevention")) {
            for (JsonNode item : Json.list(report, section)) {
                Json.require(itemIds.add(Json.text(item, "id")), "Duplicate report item ID");
                Json.text(item, "description");
                var references = Json.list(item, "evidenceIds");
                if (section.equals("facts")) Json.require(!references.isEmpty(), "Fact has no direct citations");
                for (JsonNode reference : references) {
                    Json.require(reference.isString() && evidence.containsKey(reference.asText()), "Report references absent/foreign evidence");
                    cited.add(reference.asText());
                }
                if (section.equals("hypotheses")) {
                    String level = Json.text(item, "supportLevel");
                    Json.require(Set.of("SUPPORTED", "PARTIAL", "UNVERIFIED").contains(level), "Invalid support level");
                    var limitations = Json.list(item, "limitations");
                    if (!level.equals("SUPPORTED")) Json.require(!limitations.isEmpty(), "Uncertainty requires a stated limitation");
                    if (!level.equals("UNVERIFIED")) {
                        Json.require(!references.isEmpty(), "Supported hypothesis has no direct citations");
                        for (JsonNode reference : references) supported.add(reference.asText());
                    }
                }
                if (section.equals("actions")) Json.require(item.path("requiresHumanAction").asBoolean(), "Action is not a human-reviewed proposal");
                if (section.equals("prevention")) {
                    Json.require(!Json.list(item, "validationSteps").isEmpty(), "Prevention has no verification steps");
                    for (JsonNode path : Json.list(item, "targetPaths")) {
                        boolean referencedCode = false;
                        for (JsonNode reference : references) {
                            JsonNode detail = evidence.get(reference.asText());
                            if (detail != null && detail.path("type").asText().equals("CODE")
                                    && detail.path("source").path("path").equals(path)) referencedCode = true;
                        }
                        Json.require(referencedCode, "Prevention target does not directly cite its observed source");
                    }
                }
            }
        }
        var missing = Json.list(report, "missingInformation");
        var missingFields = new HashSet<String>();
        for (JsonNode item : missing) {
            String field = Json.text(item, "field"); Json.text(item, "reason");
            Json.require(MISSING.contains(field) && missingFields.add(field), "Invalid/duplicate requested input field");
        }
        if (caseId.equals("NEEDS_INPUT")) {
            Json.require(!missing.isEmpty(), "Information-poor inquiry did not ask for missing information");
            Json.require(supported.isEmpty(), "Information-poor inquiry asserted a supported cause");
            return;
        }
        Json.require(missing.isEmpty() && !report.path("facts").isEmpty(), "Complete investigation lacks facts or still needs user input");
        var citedTypes = new HashSet<String>(); var citedTables = new HashSet<String>(); var citedEvents = new HashSet<String>();
        for (String id : cited) {
            JsonNode detail = evidence.get(id);
            String type = detail.path("type").asText(); citedTypes.add(type);
            if (type.equals("DATA")) citedTables.add(detail.path("source").path("table").asText());
            if (type.equals("LOG")) for (JsonNode event : detail.path("content")) citedEvents.add(event.path("event").asText());
        }
        Json.require(citedTables.containsAll(TABLES.get(caseId)), "Report did not cite the case's required database observations");
        Json.require(citedEvents.containsAll(EVENTS.get(caseId)), "Report did not cite the case's actual business events");
        Json.require(citedTypes.containsAll(Set.of("DATA", "LOG", "POLICY")), "Report lacks data, log, or policy citations");
        if (caseId.equals("NORMAL")) {
            Json.require(report.path("hypotheses").isEmpty(), "Normal control was assigned an incident cause");
        } else {
            Json.require(citedTypes.contains("CODE") && !supported.isEmpty(), "Incident lacks supported cause and runtime source citations");
            Json.require(!report.path("actions").isEmpty() && !report.path("prevention").isEmpty(), "Incident lacks human action or recurrence prevention");
            var causeTypes = new HashSet<String>();
            for (String id : supported) causeTypes.add(evidence.get(id).path("type").asText());
            Json.require(causeTypes.containsAll(Set.of("DATA", "LOG", "CODE")), "Cause needs direct data, log, and runtime code citations");
        }
        Json.require(!Json.list(investigation, "progress").isEmpty(), "No stored tool executions were observed");
        var toolEvidence = new HashSet<String>();
        for (JsonNode execution : investigation.path("progress")) {
            Json.require(execution.path("status").asText().equals("SUCCEEDED"), "Tool execution did not succeed");
            for (JsonNode id : Json.list(execution, "evidenceIds")) {
                Json.require(evidence.containsKey(id.asText()), "Tool execution references foreign evidence");
                toolEvidence.add(id.asText());
            }
        }
        Json.require(toolEvidence.containsAll(evidence.keySet()), "Saved evidence lacks its actual tool execution");
    }
}
