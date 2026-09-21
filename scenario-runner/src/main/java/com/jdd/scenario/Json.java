package com.jdd.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    private Json() {}

    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static ArrayNode array() { return MAPPER.createArrayNode(); }
    static ObjectNode object(Object... pairs) {
        var result = object();
        for (int i = 0; i < pairs.length; i += 2) result.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
        return result;
    }
    static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isString() && !node.asString().isBlank(), "Missing text: " + field);
        return node.asString();
    }
    static ArrayNode list(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isArray(), "Missing array: " + field);
        return (ArrayNode) node;
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new VerificationFailure(message);
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static JsonNode read(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "A regular JSON artifact is required");
        require(Files.size(path) <= 32L * 1024 * 1024, "JSON artifact exceeds 32 MiB");
        return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }
    static void write(Path path, JsonNode node) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static final class VerificationFailure extends RuntimeException {
        VerificationFailure(String message) { super(message); }
    }
}
