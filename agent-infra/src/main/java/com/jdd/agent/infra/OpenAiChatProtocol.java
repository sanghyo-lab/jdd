package com.jdd.agent.infra;

import com.jdd.agent.domain.InvestigationModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/** Maps the server's persisted evidence conversation to Spring AI. Owns no transport or tool execution. */
public final class OpenAiChatProtocol {
    private final JsonMapper json;
    private final String reportSchema;
    public OpenAiChatProtocol(JsonMapper json) { this.json = json; this.reportSchema = json.writeValueAsString(reportSchema()); }

    public Prompt prompt(InvestigationModel.Request request, OpenAiChatOptions options) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(request.prompt().text()));
        messages.add(new UserMessage(json.writeValueAsString(request.input())));
        for (var item : request.history()) {
            switch (item.kind()) {
                case ASSISTANT -> messages.add(AssistantMessage.builder().content(item.text() == null ? "" : item.text())
                        .toolCalls(item.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.argumentsJson())).toList()).build());
                case TOOL -> messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        item.toolCallId(), item.toolName(), json.writeValueAsString(Map.of("summary", item.text(), "evidence", item.observations()))))).build());
                case FEEDBACK -> messages.add(new UserMessage(item.text()));
            }
        }
        List<ToolCallback> callbacks = request.tools().stream().map(definition -> (ToolCallback) new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(definition.name()).description(definition.description()).inputSchema(definition.inputSchemaJson()).build();
            }
            @Override public String call(String input) { throw new IllegalStateException("Only InvestigationRunner may execute evidence tools"); }
        }).toList();
        var actual = options.mutate().toolCallbacks(callbacks).strict(true).parallelToolCalls(false).store(false)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .strict(true).jsonSchema(reportSchema).build()).build();
        return new Prompt(messages, actual);
    }
    public String reportSchemaJson() { return reportSchema; }
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
