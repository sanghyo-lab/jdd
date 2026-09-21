package com.jdd.agent;

import com.jdd.agent.infra.*;
import com.jdd.agent.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ResponsesSseTest {
    final JsonMapper json = JsonMapper.builder().build();
    InputStream fragmented(String value, int chunk) {
        return new FilterInputStream(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(chunk, len)); }
        };
    }
    String event(Object value) { return "data: " + json.writeValueAsString(value) + "\r\n\r\n"; }
    @Test void everyChunkBoundaryPreservesTextToolArgumentsReasoningAndFollowup() throws Exception {
        var reasoning = Map.of("type", "reasoning", "id", "reason-1", "summary", List.of(), "encrypted_content", "opaque-reasoning");
        var tool = Map.of("type", "function_call", "call_id", "call-1", "name", "getInventoryContext", "arguments", "{\"productId\":\"한국\"}");
        String stream = ": keepalive\r\n\r\n" + event(Map.of("type", "response.output_text.delta", "delta", "안녕"))
                + event(Map.of("type", "response.output_item.done", "output_index", 0, "item", reasoning))
                + event(Map.of("type", "response.function_call_arguments.delta", "delta", "{\"prod"))
                + event(Map.of("type", "response.output_item.done", "output_index", 1, "item", tool))
                + event(Map.of("type", "response.completed", "response", Map.of("id", "resp-1", "status", "completed")));
        for (int chunk = 1; chunk <= 64; chunk++) {
            var deltas = new ArrayList<String>();
            var completed = ResponsesSse.read(fragmented(stream, chunk), json, deltas::add);
            assertThat(deltas).containsExactly("안녕");
            var protocol = new ResponsesProtocol(json); var reply = protocol.reply(completed);
            assertThat(reply.toolCalls()).containsExactly(new InvestigationModel.ToolCall("call-1", "getInventoryContext", "{\"productId\":\"한국\"}"));
            var request = new InvestigationModel.Request("test", new InvestigationInput("1.0", "ticket", 1, "key", "문의", null, null),
                    InvestigationPromptLoader.load(), 2, List.of(), List.of(InvestigationModel.Message.assistant(reply),
                    InvestigationModel.Message.tool(reply.toolCalls().getFirst(), List.of(), "저장 근거")));
            String sent = json.writeValueAsString(protocol.payload(request, "explicit-model"));
            assertThat(sent).contains("opaque-reasoning", "function_call_output", "call-1", "저장 근거");
        }
    }
    @Test void incompleteEofMalformedAndDoneSentinelNeverBecomeSuccessfulReports() {
        for (String bad : List.of("", "data: {broken}\n\n", "data: [DONE]\n\n",
                event(Map.of("type", "response.output_text.delta", "delta", "partial")),
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}")) {
            assertThatThrownBy(() -> ResponsesSse.read(fragmented(bad, 1), json, ignored -> {})).isInstanceOf(IOException.class);
        }
    }
    @Test void terminalFailureAndIncompleteRemainFailuresEvenAfterTextDeltas() throws Exception {
        for (String state : List.of("failed", "incomplete")) {
            var response = ResponsesSse.read(fragmented(event(Map.of("type", "response.output_text.delta", "delta", "partial"))
                    + event(Map.of("type", "response." + state, "response", Map.of("status", state))), 2), json, ignored -> {});
            assertThatThrownBy(() -> new ResponsesProtocol(json).reply(response)).isInstanceOf(InvestigationFailure.class);
        }
    }
    @Test void multilineDataAndCrOnlyAreFramedAndUnknownEventsAreIgnored() throws Exception {
        String stream = "event: future.event\rdata: {\"type\":\"future.event\"}\r\r"
                + "data: {\"type\":\"response.completed\",\rdata: \"response\":{\"status\":\"completed\",\"output\":[]}}\r\r";
        assertThat(ResponsesSse.read(fragmented(stream, 1), json, ignored -> {}).path("status").asText()).isEqualTo("completed");
    }
}
