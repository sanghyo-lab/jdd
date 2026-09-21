package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Text, strict report schema and function calls only. No client-side tool execution. */
public final class ResponsesProtocol {
    private final JsonMapper json;
    public ResponsesProtocol(JsonMapper json) { this.json = json; }

    public Map<String, Object> payload(InvestigationModel.Request request, String model) {
        var input = new ArrayList<Object>();
        input.add(Map.of("role", "user", "content", json.writeValueAsString(request.input())));
        for (var message : request.history()) {
            switch (message.kind()) {
                case ASSISTANT -> {
                    if (!message.responseItems().isEmpty()) {
                        for (String item : message.responseItems()) input.add(json.readTree(item));
                    } else {
                        if (message.text() != null && !message.text().isEmpty())
                            input.add(Map.of("role", "assistant", "content", message.text()));
                        for (var call : message.toolCalls()) input.add(Map.of("type", "function_call",
                                "call_id", call.id(), "name", call.name(), "arguments", call.argumentsJson()));
                    }
                }
                case TOOL -> input.add(Map.of("type", "function_call_output", "call_id", message.toolCallId(),
                        "output", json.writeValueAsString(Map.of("summary", message.text(), "evidence", message.observations()))));
                case FEEDBACK -> input.add(Map.of("role", "user", "content", message.text()));
            }
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model); body.put("instructions", request.prompt().text()); body.put("input", input);
        body.put("tools", request.tools().stream().map(tool -> Map.of("type", "function", "name", tool.name(),
                "description", tool.description(), "parameters", json.readTree(tool.inputSchemaJson()), "strict", true)).toList());
        body.put("tool_choice", "auto"); body.put("parallel_tool_calls", false);
        body.put("store", false); body.put("stream", true); body.put("include", List.of("reasoning.encrypted_content"));
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "investigation_report",
                "strict", true, "schema", reportSchema())));
        return body;
    }
    public InvestigationModel.Reply reply(JsonNode response) {
        if (!"completed".equals(response.path("status").asText()) || !response.path("output").isArray())
            throw InvestigationFailure.unavailable();
        var text = new StringBuilder();
        var calls = new ArrayList<InvestigationModel.ToolCall>();
        var items = new ArrayList<String>();
        for (var item : response.path("output")) {
            String type = item.path("type").asText();
            if (!Set.of("message", "function_call", "reasoning").contains(type))
                throw InvestigationFailure.modelConfiguration(); // No hosted, shell, image or custom tools.
            items.add(json.writeValueAsString(item));
            if (type.equals("function_call")) {
                String id = item.path("call_id").asText(""), name = item.path("name").asText("");
                if (id.isBlank() || name.isBlank() || !item.path("arguments").isString()) throw InvestigationFailure.unavailable();
                calls.add(new InvestigationModel.ToolCall(id, name, item.path("arguments").asText()));
            } else if (type.equals("message")) {
                for (var part : item.path("content")) {
                    if ("refusal".equals(part.path("type").asText()))
                        throw new InvestigationFailure(new Investigation.ApiError("REPORT_VALIDATION_FAILED", "모델이 조사 보고서를 반환하지 못했습니다.", false));
                    if ("output_text".equals(part.path("type").asText())) text.append(part.path("text").asText());
                }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) throw InvestigationFailure.unavailable();
        return new InvestigationModel.Reply(text.toString(), List.copyOf(calls), List.copyOf(items));
    }
    private static Map<String, Object> reportSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("schemaVersion", Map.of("type", "string", "enum", List.of("1.0")));
        properties.put("summary", text());
        properties.put("facts", array(object(Map.of("id", text(), "description", text(), "evidenceIds", array(text())))));
        properties.put("hypotheses", array(object(Map.of("id", text(), "description", text(), "supportLevel", Map.of("type", "string",
                "enum", List.of("SUPPORTED", "PARTIAL", "UNVERIFIED")), "evidenceIds", array(text()), "limitations", array(text())))));
        properties.put("actions", array(object(Map.of("id", text(), "description", text(), "evidenceIds", array(text()), "requiresHumanAction", Map.of("type", "boolean")))));
        properties.put("prevention", array(object(Map.of("id", text(), "description", text(), "targetPaths", array(text()), "evidenceIds", array(text()), "validationSteps", array(text())))));
        properties.put("missingInformation", array(object(Map.of("field", text(), "reason", text()))));
        return object(properties);
    }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> text() { return Map.of("type", "string"); }
}
