package com.jdd.agent.infra;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SSE framing is independent of HTTP chunks and UTF-8 byte boundaries. Partial output is never a report. */
public final class ResponsesSse {
    private ResponsesSse() {}
    public static JsonNode read(InputStream input, JsonMapper json, Consumer<String> delta) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()));
        var data = new StringBuilder(); var line = new StringBuilder();
        var items = new TreeMap<Integer, JsonNode>();
        int total = 0, count;
        boolean previousCr = false;
        while ((count = reader.read()) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Model call cancelled");
            if (++total > 4 * 1024 * 1024) throw new IOException("Model stream exceeds limit");
            char ch = (char) count;
            if (previousCr && ch == '\n') { previousCr = false; continue; }
            previousCr = ch == '\r';
            if (ch != '\r' && ch != '\n') { line.append(ch); continue; }
            String value = line.toString(); line.setLength(0);
            if (value.isEmpty()) {
                if (data.isEmpty()) continue;
                String eventData = data.toString(); data.setLength(0);
                if (eventData.equals("[DONE]")) throw new IOException("Stream ended without response.completed");
                final JsonNode event;
                try { event = json.readTree(eventData); }
                catch (RuntimeException invalid) { throw new IOException("Invalid model stream event"); }
                String type = event.path("type").asText();
                if (type.equals("response.output_text.delta")) {
                    if (!event.path("delta").isString()) throw new IOException("Invalid text delta");
                    delta.accept(event.path("delta").asText());
                } else if (type.equals("response.output_item.done")) {
                    if (!event.path("output_index").isIntegralNumber() || !event.path("item").isObject())
                        throw new IOException("Invalid output item");
                    int index = event.path("output_index").asInt(-1);
                    if (index < 0 || items.putIfAbsent(index, event.path("item")) != null)
                        throw new IOException("Duplicate output item");
                } else if (type.equals("response.completed") || type.equals("response.failed") || type.equals("response.incomplete")) {
                    if (!(event.path("response") instanceof ObjectNode response)) throw new IOException("Missing terminal response");
                    String expected = type.substring("response.".length());
                    if (!expected.equals(response.path("status").asText())) throw new IOException("Conflicting terminal status");
                    if (expected.equals("completed") && (!response.path("output").isArray() || response.path("output").isEmpty()) && !items.isEmpty()) {
                        var output = response.putArray("output"); items.values().forEach(output::add);
                    }
                    return response;
                } else if (type.equals("error")) {
                    var failure = json.createObjectNode(); failure.put("status", "failed");
                    failure.set("error", event); return failure;
                }
            } else if (value.startsWith("data:")) {
                String content = value.substring(5);
                if (content.startsWith(" ")) content = content.substring(1);
                if (!data.isEmpty()) data.append('\n');
                data.append(content);
            }
        }
        throw new EOFException("Model stream closed before response.completed");
    }
}
