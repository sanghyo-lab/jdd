package com.jdd.scenario;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ReportChecksTest {
    Map<String, JsonNode> normalEvidence() {
        return Map.of("orders", Json.object("type", "DATA", "source", Json.object("table", "orders")),
                "payments", Json.object("type", "DATA", "source", Json.object("table", "payments")),
                "log", Json.object("type", "LOG", "content", Json.object("entry", Json.object("event", "PAYMENT_APPROVED"))),
                "policy", Json.object("type", "POLICY"));
    }
    ObjectNode normalInvestigation() {
        var ids = List.of("orders", "payments", "log", "policy");
        var report = Json.object("schemaVersion", "1.0", "summary", "정상 카드 승인과 결제 완료 상태가 일치합니다.",
                "facts", List.of(Json.object("id", "fact-1", "description", "승인 후 결제 완료입니다.", "evidenceIds", ids)),
                "hypotheses", List.of(Json.object("id", "normal-explanation", "description", "정상 상태 전이로 설명됩니다.",
                        "supportLevel", "SUPPORTED", "evidenceIds", ids, "limitations", List.of())),
                "actions", List.of(), "prevention", List.of(), "missingInformation", List.of());
        return Json.object("schemaVersion", "1.0", "error", null, "report", report,
                "progress", List.of(Json.object("status", "SUCCEEDED", "evidenceIds", ids)));
    }
    @Test void normalExplanationHypothesisIsNotConfusedWithAnIncident() {
        assertDoesNotThrow(() -> ReportChecks.verify("NORMAL", normalInvestigation(), normalEvidence()));
    }
    @Test void readsActualLogEntryContractAndRejectsMissingEvent() {
        var evidence = new java.util.HashMap<>(normalEvidence());
        evidence.put("log", Json.object("type", "LOG", "content", Json.object("entry", Json.object("event", "OTHER"))));
        assertThrows(Json.VerificationFailure.class, () -> ReportChecks.verify("NORMAL", normalInvestigation(), evidence));
    }
    @Test void rejectsForeignEvidenceAndUnobservedPreventionPath() {
        var investigation = normalInvestigation();
        ((ObjectNode) investigation.path("report").path("facts").get(0)).set("evidenceIds", Json.MAPPER.valueToTree(List.of("foreign")));
        assertThrows(Json.VerificationFailure.class, () -> ReportChecks.verify("NORMAL", investigation, normalEvidence()));
        var original = normalInvestigation();
        ((ObjectNode) original.path("report")).set("prevention", Json.MAPPER.valueToTree(List.of(Json.object(
                "id", "fix", "description", "제안", "evidenceIds", List.of("orders"),
                "validationSteps", List.of("확인"), "targetPaths", List.of("unobserved.java")))));
        assertThrows(Json.VerificationFailure.class, () -> ReportChecks.verify("NORMAL", original, normalEvidence()));
    }
    @Test void insufficientInputMustRequestAnAllowedField() {
        var report = Json.object("schemaVersion", "1.0", "summary", "조사할 주문 정보가 필요합니다.",
                "facts", List.of(), "hypotheses", List.of(), "actions", List.of(), "prevention", List.of(),
                "missingInformation", List.of(Json.object("field", "context.orderId", "reason", "주문을 식별해야 합니다.")));
        var investigation = Json.object("schemaVersion", "1.0", "error", null, "report", report);
        assertDoesNotThrow(() -> ReportChecks.verify("NEEDS_INPUT", investigation, Map.of()));
        ((ObjectNode) investigation.path("report").path("missingInformation").get(0)).put("field", "apiKey");
        assertThrows(Json.VerificationFailure.class, () -> ReportChecks.verify("NEEDS_INPUT", investigation, Map.of()));
    }
}
