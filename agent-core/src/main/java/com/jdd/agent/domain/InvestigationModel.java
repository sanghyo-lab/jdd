package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.EvidenceDetail;
import java.util.List;

/** One model request. Tool execution and retries belong to InvestigationRunner, not an advisor. */
public interface InvestigationModel {
    enum Mode { DISABLED, MOCK, OPENAI, CODEX_OAUTH, UNKNOWN }
    default Mode mode() { return Mode.UNKNOWN; }
    record Prompt(String version, String sha256, String text) {}
    record ToolDefinition(String name, String description, String inputSchemaJson) {}
    record ToolCall(String id, String name, String argumentsJson) {}
    // Opaque Responses output items preserve encrypted reasoning across tool turns.
    // They are internal model context, never evidence or browser-visible output.
    record Reply(String text, List<ToolCall> toolCalls, List<String> responseItems) {
        public Reply(String text, List<ToolCall> toolCalls) { this(text, toolCalls, List.of()); }
    }
    enum MessageKind { ASSISTANT, TOOL, FEEDBACK }
    record Message(MessageKind kind, String text, List<ToolCall> toolCalls, String toolCallId,
                   String toolName, List<EvidenceDetail> observations, List<String> responseItems) {
        public Message(MessageKind kind, String text, List<ToolCall> toolCalls, String toolCallId,
                       String toolName, List<EvidenceDetail> observations) {
            this(kind, text, toolCalls, toolCallId, toolName, observations, List.of());
        }
        public static Message assistant(Reply reply) {
            return new Message(MessageKind.ASSISTANT, reply.text(), reply.toolCalls(), null, null, List.of(), reply.responseItems());
        }
        public static Message tool(ToolCall call, List<EvidenceDetail> observations, String message) {
            return new Message(MessageKind.TOOL, message, List.of(), call.id(), call.name(), List.copyOf(observations));
        }
        public static Message feedback(String message) {
            return new Message(MessageKind.FEEDBACK, message, List.of(), null, null, List.of());
        }
    }
    record Request(String investigationId, InvestigationInput input, Prompt prompt, int iteration,
                   List<ToolDefinition> tools, List<Message> history) {}

    Reply next(Request request);
}
