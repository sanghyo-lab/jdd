package com.jdd.scenario;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerTest {
    @TempDir Path root;

    @Test void missingOptInPreservesAllPendingCasesAndNeverCreatesLiveSuccess() throws Exception {
        Path report = root.resolve("new-result.json");
        assertEquals(1, new ScenarioRunner(root, Map.of(), Duration.ofMillis(20), 1).run(report));
        var value = Json.read(report);
        assertEquals("rejected", value.path("mode").asText());
        assertTrue(value.path("model").isNull());
        assertEquals(11, value.path("cases").size());
        for (var item : value.path("cases")) assertEquals("PENDING", item.path("status").asText());
        assertFalse(Files.exists(root.resolve("new-result.json.artifacts/http-observations.jsonl")));
    }

    @Test void mockAndMissingPreparedEvidenceNeverStartBusinessHttp() throws Exception {
        var mock = Map.of("JDD_MVP_LIVE", "true", "APP_RUNTIME", "test", "LLM_PROVIDER", "mock");
        assertEquals(1, new ScenarioRunner(root, mock, Duration.ofMillis(20), 1).run(root.resolve("mock.json")));
        var local = Map.of("JDD_MVP_LIVE", "true", "APP_RUNTIME", "local", "LLM_PROVIDER", "codex_oauth", "CODEX_MODEL", "test-selection");
        assertEquals(1, new ScenarioRunner(root, local, Duration.ofMillis(20), 1).run(root.resolve("missing.json")));
        assertFalse(Files.exists(root.resolve("mock.json.artifacts/http-observations.jsonl")));
        assertFalse(Files.exists(root.resolve("missing.json.artifacts/http-observations.jsonl")));
    }

    @Test void refusesToOverwritePreviousResults() throws Exception {
        Path report = root.resolve("existing.json");
        Files.writeString(report, "previous result must remain unchanged");
        assertThrows(Json.VerificationFailure.class,
                () -> new ScenarioRunner(root, Map.of(), Duration.ofMillis(20), 1).run(report));
        assertEquals("previous result must remain unchanged", Files.readString(report));
    }
}
