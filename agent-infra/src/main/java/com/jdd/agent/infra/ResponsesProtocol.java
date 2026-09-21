package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Text, strict report schema and function calls only. No client-side tool execution. */
public final class ResponsesProtocol {
    private static final System.Logger LOG = System.getLogger(ResponsesProtocol.class.getName());
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
        if (request.remaining() != null) {
            input.add(Map.of("role", "user", "content", "서버 실행 한도: 이번 응답을 포함해 모델 응답 "
                    + request.remaining().modelCalls() + "회, 추가 조회 도구 " + request.remaining().toolCalls()
                    + "회가 남았습니다. 마지막 모델 응답은 저장된 근거로 보고서를 작성하는 데 사용합니다. "
                    + "서로 의존하지 않는 필요한 조회는 한 응답에서 함께 요청하세요. 도구가 제공되지 않으면 "
                    + "추가 조회 없이 보고서를 반환하고 확인하지 못한 범위를 명시하세요. 한도 부족을 사용자 입력 부족으로 바꾸지 마세요."));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model); body.put("instructions", request.prompt().text()); body.put("input", input);
        body.put("tools", request.tools().stream().map(tool -> Map.of("type", "function", "name", tool.name(),
                "description", tool.description(), "parameters", json.readTree(tool.inputSchemaJson()), "strict", true)).toList());
        body.put("tool_choice", request.tools().isEmpty() ? "none" : "auto");
        // One model response may request multiple allowed reads; the service still executes them sequentially.
        body.put("parallel_tool_calls", true);
        body.put("store", false); body.put("stream", true); body.put("include", List.of("reasoning.encrypted_content"));
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "investigation_report",
                "strict", true, "schema", reportSchema(request))));
        return body;
    }
    public InvestigationModel.Reply reply(JsonNode response) {
        if (!"completed".equals(response.path("status").asText()) || !response.path("output").isArray())
            throw InvestigationFailure.unavailable();
        var unphasedText = new StringBuilder();
        var finalText = new StringBuilder();
        int finalMessages = 0, unphasedMessages = 0, commentaryMessages = 0;
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
                StringBuilder target;
                var phase = item.path("phase");
                if (phase.isMissingNode() || phase.isNull()) {
                    unphasedMessages++; target = unphasedText;
                } else if (phase.isString() && phase.asText().equals("final_answer")) {
                    finalMessages++; target = finalText;
                } else if (phase.isString() && phase.asText().equals("commentary")) {
                    commentaryMessages++; target = null;
                } else throw InvestigationFailure.modelConfiguration();
                for (var part : item.path("content")) {
                    if ("refusal".equals(part.path("type").asText()))
                        throw new InvestigationFailure(new Investigation.ApiError("REPORT_VALIDATION_FAILED", "모델이 조사 보고서를 반환하지 못했습니다.", false));
                    if (target != null && "output_text".equals(part.path("type").asText())) target.append(part.path("text").asText());
                }
            }
        }
        var text = finalMessages > 0 ? finalText : unphasedText;
        LOG.log(System.Logger.Level.INFO, "Model output selection: finalMessages={0}, unphasedMessages={1}, commentaryMessages={2}, toolCalls={3}",
                finalMessages, unphasedMessages, commentaryMessages, calls.size());
        if (text.isEmpty() && calls.isEmpty() && commentaryMessages == 0 && finalMessages == 0)
            throw InvestigationFailure.unavailable();
        // An interim-only response gets bounded report feedback, never a completed report.
        return new InvestigationModel.Reply(text.toString(), List.copyOf(calls), List.copyOf(items));
    }
    private static Map<String, Object> reportSchema(InvestigationModel.Request request) {
        var ids = request.history().stream().filter(message -> message.kind() == InvestigationModel.MessageKind.TOOL)
                .flatMap(message -> message.observations().stream()).map(Investigation.EvidenceDetail::evidenceId)
                .distinct().toList();
        // One shared definition avoids repeating long IDs in every report section. Large histories
        // retain all observations and the existing server validation rather than truncating choices.
        boolean constrainIds = !ids.isEmpty() && ids.size() <= 250
                && ids.stream().mapToInt(String::length).sum() <= 9000;
        var references = array(constrainIds ? Map.of("$ref", "#/$defs/storedEvidenceId") : text());
        var properties = new LinkedHashMap<String, Object>();
        properties.put("schemaVersion", Map.of("type", "string", "enum", List.of("1.0")));
        properties.put("summary", text());
        properties.put("facts", array(object(Map.of("id", text(), "description", text(), "evidenceIds", references))));
        properties.put("hypotheses", array(object(Map.of("id", text(), "description", text(), "supportLevel", Map.of("type", "string",
                "enum", List.of("SUPPORTED", "PARTIAL", "UNVERIFIED")), "evidenceIds", references, "limitations", array(text())))));
        properties.put("actions", array(object(Map.of("id", text(), "description", text(), "evidenceIds", references, "requiresHumanAction", Map.of("type", "boolean")))));
        properties.put("prevention", array(object(Map.of("id", text(), "description", text(), "targetPaths", array(text()), "evidenceIds", references, "validationSteps", array(text())))));
        properties.put("missingInformation", array(object(Map.of("field", text(), "reason", text()))));
        var schema = new LinkedHashMap<String, Object>(object(properties));
        if (constrainIds) schema.put("$defs", Map.of("storedEvidenceId", Map.of("type", "string", "enum", ids)));
        return schema;
    }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> text() { return Map.of("type", "string"); }
}
