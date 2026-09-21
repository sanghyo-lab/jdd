package com.jdd.agent;

import com.jdd.agent.domain.InvestigationTools;
import com.jdd.agent.domain.InvestigationModel.ToolCall;
import com.jdd.agent.infra.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "jdd.agent.worker.enabled=false", "spring.datasource.url=jdbc:h2:mem:file-tools;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class EvidenceFileToolsTest {
    @Autowired JsonMapper json;
    @TempDir Path temp;
    private static final String BUILD = "test-build", CODE = "commerce-core/src/main/java/Example.java";
    private Path sources, logs, policy;
    private ReadOnlyInvestigationTools tools;

    @BeforeEach void prepare() throws Exception {
        sources = temp.resolve("source"); logs = temp.resolve("logs"); policy = temp.resolve("policy.md");
        Files.createDirectories(sources.resolve(BUILD)); Files.createDirectories(logs.resolve(BUILD));
        String text = "package test;\nclass Example {\n int stock = 1;\n // literal a.*b\n}\n";
        writeSource(CODE, text);
        writeSource("fixtures/expected.java", "a.*b expected answer");
        writeSource("commerce-app/src/reproduction/java/Barrier.java", "a.*b reproduction answer");
        writeSource("commerce-core/src/test/java/Test.java", "a.*b test answer");
        manifest(Map.of(CODE, hash(text), "fixtures/expected.java", hash("a.*b expected answer"),
                "commerce-app/src/reproduction/java/Barrier.java", hash("a.*b reproduction answer"),
                "commerce-core/src/test/java/Test.java", hash("a.*b test answer")));
        Files.writeString(policy, "# 정책\n적용 버전은 `demo-v1`이다.\n\n## 재고\n재고는 음수가 될 수 없다.\n\n## 결제\n결제 정책\n");
        tools = new ReadOnlyInvestigationTools(null, new LogEvidenceTools(logs, json, Clock.systemUTC()),
                new SourceEvidenceTools(sources, policy, json, Clock.systemUTC()), json);
    }
    @Test void schemasAreStrictAndArgumentsCannotBecomeSqlOrUnboundedScans() {
        assertThat(tools.definitions()).hasSize(8);
        for (var tool : tools.definitions()) {
            var schema = json.readTree(tool.inputSchemaJson());
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("required").size()).isEqualTo(schema.path("properties").size());
        }
        for (String input : List.of("null", "{}", "{\"productId\":1}", "{\"productId\":\"p\",\"limit\":1.5}",
                "{\"productId\":\"p\",\"limit\":\"2\"}", "{\"productId\":\"p\",\"limit\":101}",
                "{\"productId\":\"p\",\"sql\":\"DELETE FROM commerce.products\"}"))
            assertThat(tools.validate(new ToolCall("1", "getInventoryContext", input))).as(input).isNotEmpty();
        assertThat(tools.validate(call("findOrders", Map.of("limit", 20)))).isNotEmpty();
        assertThat(tools.validate(call("searchLogs", Map.of("from", "2026-09-20T00:00:00Z", "to", "2026-09-22T00:00:00Z")))).isNotEmpty();
        assertThat(tools.validate(call("readCode", Map.of("buildId", BUILD, "path", CODE, "startLine", 1, "endLine", 301)))).isNotEmpty();
        assertThat(tools.validate(call("getInventoryContext", Map.of("productId", "p' OR 1=1 --", "limit", 1)))).isEmpty();
    }
    @Test void literalCodeSearchExcludesFixturesTestsAndReproductionAndReturnsOriginalLines() {
        var result = run("searchCode", Map.of("buildId", BUILD, "query", "a.*b"));
        assertThat(result.observations()).hasSize(1);
        var observed = result.observations().getFirst();
        assertThat(observed.source()).containsEntry("path", CODE).containsEntry("startLine", 1).containsEntry("endLine", 5);
        assertThat(observed.content().toString()).contains("// literal a.*b").doesNotContain("expected answer", "reproduction answer");
        var read = run("readCode", Map.of("buildId", BUILD, "path", CODE, "startLine", 3, "endLine", 4));
        assertThat(read.observations().getFirst().content()).isEqualTo(" int stock = 1;\n // literal a.*b");
        assertThat(run("searchCode", Map.of("buildId", BUILD, "query", "absent")).observations()).isEmpty();
    }
    @Test void codeSearchLimitIsExplicitOnEveryObservation() throws Exception {
        String text = "needle\nneedle\nneedle\n"; writeSource(CODE, text); manifest(Map.of(CODE, hash(text)));
        var result = run("searchCode", Map.of("buildId", BUILD, "query", "needle", "limit", 1));
        assertThat(result.observations()).hasSize(1).allMatch(value -> value.truncated());
        assertThat(result.summary()).contains("한도");
    }
    @Test void tamperedMissingOrDifferentBuildSourceIsNotSilentlyReplaced() throws Exception {
        Files.writeString(sources.resolve(BUILD).resolve(CODE), "tampered");
        assertThatThrownBy(() -> read(CODE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run("searchCode", Map.of("buildId", "missing-build", "query", "stock"))).isInstanceOf(IllegalStateException.class);
        var manifest = json.readTree(Files.readString(sources.resolve(BUILD).resolve("manifest.json"))).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) manifest).put("buildId", "different-build");
        Files.writeString(sources.resolve(BUILD).resolve("manifest.json"), json.writeValueAsString(manifest));
        assertThatThrownBy(() -> read(CODE)).isInstanceOf(IllegalStateException.class);
    }
    @Test void pathTraversalDisallowedRootsAndSymlinksNeverReachFiles() throws Exception {
        for (String path : List.of("../policy.md", "/etc/passwd", "commerce-core/src/main/java/../../../../policy.md", "fixtures/expected.java",
                "commerce-app/src/reproduction/java/Barrier.java", "commerce-core/src/test/java/Test.java"))
            assertThatThrownBy(() -> read(path)).isInstanceOf(IllegalStateException.class);
        Files.delete(sources.resolve(BUILD).resolve(CODE));
        Files.createSymbolicLink(sources.resolve(BUILD).resolve(CODE), policy);
        assertThatThrownBy(() -> read(CODE)).isInstanceOf(IllegalStateException.class);
    }
    @Test void policyUsesRequestedVersionAndExactSectionWithObservedHash() {
        var observed = run("readBusinessPolicy", Map.of("buildId", BUILD, "version", "demo-v1", "section", "재고")).observations().getFirst();
        assertThat(observed.content().toString()).contains("재고는 음수가").doesNotContain("결제 정책");
        assertThat(observed.source()).containsKey("sha256").containsEntry("binding", "manifest-version-and-current-policy-hash");
        assertThatThrownBy(() -> run("readBusinessPolicy", Map.of("buildId", BUILD, "version", "old"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run("readBusinessPolicy", Map.of("buildId", BUILD, "version", "demo-v1", "section", "없는 정책"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void logsMatchCorrelationPreserveRawLinesAndRepeatedEventIds() throws Exception {
        String matching = log("same-event", "request-a", "p");
        Files.writeString(logs.resolve(BUILD).resolve("business.jsonl"), log("other", "request-b", "p") + "\n" + matching + "\n" + matching + "\n");
        var result = run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a", "productId", "p"));
        assertThat(result.observations()).hasSize(2).allMatch(value -> !value.truncated());
        assertThat(result.observations().getFirst().source()).containsEntry("startLine", 2).containsEntry("endLine", 2);
        assertThat(((Map<?, ?>) result.observations().getFirst().content()).get("raw")).isEqualTo(matching);
        assertThat(result.summary()).contains("재출력 1줄");
        Files.writeString(logs.resolve(BUILD).resolve("business.jsonl"), matching + "\n"
                + matching.replace("\"observedQuantity\":1", "\"observedQuantity\":2") + "\n");
        assertThatThrownBy(() -> run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void partialAndMalformedLogFilesAreNotReportedAsCompleteAbsence() throws Exception {
        Path path = logs.resolve(BUILD).resolve("business.jsonl");
        Files.writeString(path, log("e1", "request-a", "p") + "\n" + "{\"unfinished\":");
        var result = run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a"));
        assertThat(result.observations()).hasSize(1).allMatch(value -> value.truncated());
        assertThat(result.summary()).contains("미완성");
        var empty = run("searchLogs", Map.of("buildId", BUILD, "requestId", "absent"));
        assertThat(empty.observations()).isEmpty(); assertThat(empty.summary()).contains("일부 결과");
        Files.writeString(path, "{not-json}\n");
        assertThatThrownBy(() -> run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a"))).isInstanceOf(RuntimeException.class);
        Files.writeString(path, log("e1", "request-a", "p").replace(BUILD, "wrong-build") + "\n");
        assertThatThrownBy(() -> run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void logLimitAndDelayedOutboxRemainBoundedWithoutModelInvocation() throws Exception {
        Path path = logs.resolve(BUILD).resolve("business.jsonl");
        Files.writeString(path, log("e1", "request-a", "p") + "\n" + log("e2", "request-a", "p") + "\n");
        var limited = run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a", "limit", 1));
        assertThat(limited.observations()).hasSize(1).allMatch(value -> value.truncated());
        Files.writeString(path, "");
        try (var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            executor.schedule(() -> { try { Files.writeString(path, log("late", "request-a", "p") + "\n"); }
                catch (Exception error) { throw new RuntimeException(error); } }, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            var result = run("searchLogs", Map.of("buildId", BUILD, "requestId", "request-a"));
            assertThat(result.observations()).hasSize(1);
            assertThat(result.summary()).contains("로컬 조회 2회");
        }
    }
    private void read(String path) { run("readCode", Map.of("buildId", BUILD, "path", path, "startLine", 1, "endLine", 5)); }
    private InvestigationTools.Outcome run(String name, Map<String, Object> input) { return tools.execute(call(name, input)); }
    private ToolCall call(String name, Map<String, Object> input) { return new ToolCall("test", name, json.writeValueAsString(input)); }
    private void writeSource(String path, String value) throws Exception {
        Path target = sources.resolve(BUILD).resolve(path); Files.createDirectories(target.getParent()); Files.writeString(target, value);
    }
    private void manifest(Map<String, String> hashes) throws Exception {
        Files.writeString(sources.resolve(BUILD).resolve("manifest.json"), json.writeValueAsString(Map.of("schemaVersion", "1.0", "buildId", BUILD,
                "commitSha", "a".repeat(40), "policyVersion", "demo-v1", "files", hashes)));
    }
    private String log(String id, String request, String product) {
        return json.writeValueAsString(Map.of("schemaVersion", "1.0", "timestamp", "2026-09-21T00:00:00Z", "service", "commerce-app",
                "buildId", BUILD, "level", "INFO", "event", "INVENTORY_READ", "eventId", id, "requestId", request,
                "productId", product, "details", Map.of("observedQuantity", 1)));
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
