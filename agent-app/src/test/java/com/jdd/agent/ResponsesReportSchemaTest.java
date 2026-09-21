package com.jdd.agent;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationModel;
import com.jdd.agent.infra.InvestigationPromptLoader;
import com.jdd.agent.infra.ResponsesProtocol;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ResponsesReportSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ResponsesProtocol protocol = new ResponsesProtocol(json);

    @Test void onlyCurrentSavedEvidenceIdsBecomeChoicesSharedByAllReportSections() {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var body = payload(List.of(a, b, a));
        var schema = body.path("text").path("format").path("schema");
        assertThat(schema.path("$defs").path("storedEvidenceId").path("enum")).isEqualTo(json.valueToTree(List.of(a, b)));
        for (String section : List.of("facts", "hypotheses", "actions", "prevention"))
            assertThat(schema.path("properties").path(section).path("items").path("properties").path("evidenceIds")
                    .path("items").path("$ref").asText()).isEqualTo("#/$defs/storedEvidenceId");
        assertThat(schema.toString()).doesNotContain("business-record-id", "model-invented-id");
        var next = payload(List.of(b)).path("text").path("format").path("schema");
        assertThat(next.path("$defs").path("storedEvidenceId").path("enum")).isEqualTo(json.valueToTree(List.of(b)));
        assertThat(next.toString()).doesNotContain(a);
    }

    @Test void schemaBoundsNeverInventEmptyEnumsOrDropLargeHistories() {
        var ids = java.util.stream.IntStream.range(0, 251).mapToObj(i -> UUID.randomUUID().toString()).toList();
        var bounded = payload(ids.subList(0, 250)).path("text").path("format").path("schema");
        assertThat(bounded.path("$defs").path("storedEvidenceId").path("enum").size()).isEqualTo(250);
        for (List<String> values : List.of(List.<String>of(), ids, List.of("x".repeat(9001)))) {
            var body = payload(values);
            var schema = body.path("text").path("format").path("schema");
            assertThat(schema.has("$defs")).isFalse();
            assertThat(schema.path("properties").path("facts").path("items").path("properties")
                    .path("evidenceIds").path("items").path("type").asText()).isEqualTo("string");
            var toolOutput = json.readTree(body.path("input").get(1).path("output").asText());
            assertThat(toolOutput.path("evidence").size()).isEqualTo(values.size());
        }
    }

    @Test void reviewUsesTheDraftAndOriginalObservationsWithoutPriorModelSpeculationOrUncitedCode() {
        var values = List.of(observation("data", Investigation.EvidenceType.DATA),
                observation("cited-log", Investigation.EvidenceType.LOG), observation("alternate-log", Investigation.EvidenceType.LOG),
                observation("cited-code", Investigation.EvidenceType.CODE), observation("unused-code", Investigation.EvidenceType.CODE),
                observation("policy", Investigation.EvidenceType.POLICY));
        var draft = new Investigation.AnalysisReport("1.0", "Draft summary",
                List.of(new Investigation.Fact("f", "Check actual event", List.of("cited-log"))),
                List.of(new Investigation.Hypothesis("h", "Cause", Investigation.SupportLevel.SUPPORTED,
                        List.of("data", "cited-log", "cited-code", "policy"), List.of())), List.of(), List.of(), List.of());
        var request = new InvestigationModel.Request("i", new InvestigationInput("1.0", "ticket", 1, "key", "Question", null, null),
                InvestigationPromptLoader.load(), 3, List.of(), List.of(
                        InvestigationModel.Message.tool(new InvestigationModel.ToolCall("call", "searchCode", "{}"), values, "Saved"),
                        InvestigationModel.Message.assistant(new InvestigationModel.Reply("prior model speculation", List.of(),
                                List.of("{\"type\":\"reasoning\",\"encrypted_content\":\"opaque-prior-reasoning\"}"))),
                        InvestigationModel.Message.feedback("최종 인용 검수")), new InvestigationModel.Remaining(2, 0), draft);
        var body = json.valueToTree(protocol.payload(request, "explicit-model"));
        assertThat(body.path("input").toString()).doesNotContain("prior model speculation", "opaque-prior-reasoning", "unused-code");
        var bundle = json.readTree(body.path("input").get(1).path("content").asText());
        assertThat(bundle.path("reviewDraft")).isEqualTo(json.valueToTree(draft));
        assertThat(bundle.path("observations")).isEqualTo(json.valueToTree(List.of(values.get(0), values.get(1), values.get(2), values.get(3), values.get(5))));
        assertThat(body.path("tools").isEmpty()).isTrue();
        assertThat(body.path("tool_choice").asText()).isEqualTo("none");
        var format = body.path("text").path("format");
        assertThat(format.path("name").asText()).isEqualTo("investigation_report_review");
        var schema = format.path("schema");
        assertThat(schema.path("properties").has("schemaVersion")).isFalse();
        assertThat(schema.path("properties").has("missingInformation")).isFalse();
        assertThat(schema.path("properties").path("removeItemIds").path("items").path("enum"))
                .isEqualTo(json.valueToTree(List.of("f", "h")));
        assertThat(schema.path("$defs").path("storedEvidenceId").path("enum").toString()).contains("alternate-log").doesNotContain("unused-code");
    }

    private Investigation.EvidenceDetail observation(String id, Investigation.EvidenceType type) {
        return new Investigation.EvidenceDetail(id, type, "Actual synthetic observation", Instant.parse("2026-09-21T00:00:00Z"),
                Map.of("path", "commerce-core/Example.java"), Map.of("value", id), false);
    }

    private JsonNode payload(List<String> ids) {
        var observations = ids.stream().map(id -> new Investigation.EvidenceDetail(id, Investigation.EvidenceType.DATA,
                "synthetic observation", Instant.parse("2026-09-21T00:00:00Z"), Map.of("recordIds", List.of("business-record-id")), Map.of(), false)).toList();
        var call = new InvestigationModel.ToolCall("call", "getInventoryContext", "{}");
        var request = new InvestigationModel.Request("investigation", new InvestigationInput("1.0", "ticket", 1, "key",
                "model-invented-id", null, null), InvestigationPromptLoader.load(), 2, List.of(),
                List.of(InvestigationModel.Message.tool(call, observations, "saved"), InvestigationModel.Message.feedback("model-invented-id")));
        return json.valueToTree(protocol.payload(request, "explicit-model"));
    }
}
