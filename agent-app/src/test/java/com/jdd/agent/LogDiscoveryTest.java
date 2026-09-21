package com.jdd.agent;

import com.jdd.agent.infra.EvidenceToolArguments.SearchLogs;
import com.jdd.agent.infra.LogEvidenceTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class LogDiscoveryTest {
    @TempDir Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Test void emptyBuildArchivesDoNotHideTheOnlyAvailableLog() throws Exception {
        for (int i = 0; i < 40; i++) Files.createDirectory(root.resolve("build-" + i));
        Path target = entries(root).getLast();
        String raw = write(target, "business.jsonl", "target", NOW);
        var result = search(null, "target");
        assertThat(result.observations()).hasSize(1).allMatch(value -> !value.truncated());
        assertThat(result.observations().getFirst().source()).containsEntry("buildId", target.getFileName().toString());
        assertThat(((Map<?, ?>) result.observations().getFirst().content()).get("raw")).isEqualTo(raw);
        assertThat(search(target.getFileName().toString(), "target").observations()).isEqualTo(result.observations());
    }

    @Test void newestLogActivityWinsWhenNonemptyBuildsExceedTheLimit() throws Exception {
        for (int i = 0; i < 40; i++) Files.createDirectory(root.resolve("build-" + i));
        var builds = entries(root);
        for (int i = 0; i < builds.size(); i++) write(builds.get(i), "business.jsonl", "request", NOW.minusSeconds(100 + i));
        Path newest = builds.getLast();
        write(newest, "business.jsonl", "request", NOW);
        // Directory creation time is not the last activity of an existing log file.
        Files.setLastModifiedTime(newest, FileTime.from(NOW.minusSeconds(10000)));
        var result = search(null, "request");
        assertThat(result.observations()).hasSize(32).allMatch(value -> value.truncated());
        assertThat(result.observations().getFirst().source()).containsEntry("buildId", newest.getFileName().toString());
        assertThat(result.summary()).contains("일부 결과");
        String excludedOldBuild = builds.get(builds.size() - 2).getFileName().toString();
        assertThat(result.observations()).noneMatch(value -> excludedOldBuild.equals(value.source().get("buildId")));
        assertThat(search(excludedOldBuild, "request").observations()).hasSize(1).allMatch(value -> !value.truncated());
    }

    @Test void newestFilesStaySearchableWithinTheExistingFileAndLineLimits() throws Exception {
        Path build = Files.createDirectory(root.resolve("one-build"));
        for (int i = 0; i < 40; i++) Files.createFile(build.resolve("business-" + i + ".jsonl"));
        var files = entries(build);
        for (int i = 0; i < files.size(); i++) write(build, files.get(i).getFileName().toString(), "request", NOW.minusSeconds(100 + i));
        Path newest = files.getLast();
        String first = write(build, newest.getFileName().toString(), "request", NOW);
        var changed = (tools.jackson.databind.node.ObjectNode) json.readTree(first);
        changed.put("requestId", "target");
        changed.put("eventId", "target-event");
        String second = json.writeValueAsString(changed);
        Files.writeString(newest, first + "\r\n" + second + "\r\n");
        Files.setLastModifiedTime(newest, FileTime.from(NOW));
        var result = search("one-build", "target");
        assertThat(result.observations()).hasSize(1).allMatch(value -> value.truncated());
        assertThat(result.observations().getFirst().source()).containsEntry("startLine", 2).containsEntry("endLine", 2);
        assertThat(((Map<?, ?>) result.observations().getFirst().content()).get("raw")).isEqualTo(second);
    }

    @Test void metadataDiscoveryLimitDoesNotClaimCompleteAbsence() throws Exception {
        for (int i = 0; i < 4100; i++) Files.createDirectory(root.resolve("empty-" + i));
        var result = search(null, "absent");
        assertThat(result.observations()).isEmpty();
        assertThat(result.summary()).contains("일부 결과", "로컬 조회 1회");
    }

    @Test void fileBudgetIsSharedAcrossBuildsAndCountsOnlyReadFiles() throws Exception {
        for (int buildNumber = 0; buildNumber < 2; buildNumber++) {
            Path build = Files.createDirectory(root.resolve("build-" + buildNumber));
            for (int fileNumber = 0; fileNumber < 20; fileNumber++)
                write(build, "business-" + fileNumber + ".jsonl", "request", NOW.minusSeconds(buildNumber * 20 + fileNumber));
        }
        var result = search(null, "request");
        assertThat(result.observations()).hasSize(32).allMatch(value -> value.truncated());
        assertThat(result.summary()).contains("파일 32개", "일부 결과");
    }

    private com.jdd.agent.domain.InvestigationTools.Outcome search(String build, String request) {
        return new LogEvidenceTools(root, json, Clock.fixed(NOW, java.time.ZoneOffset.UTC))
                .search(new SearchLogs(build, request, null, null, null, null, null, 100));
    }
    private String write(Path build, String file, String request, Instant changed) throws Exception {
        String raw = json.writeValueAsString(Map.of("schemaVersion", "1.0", "service", "commerce-app",
                "buildId", build.getFileName().toString(), "event", "ORDER_CREATED", "requestId", request,
                "timestamp", NOW.toString(), "details", Map.of(), "eventId", build.getFileName() + "/" + file + "/" + request));
        Path path = build.resolve(file);
        Files.writeString(path, raw + "\n");
        Files.setLastModifiedTime(path, FileTime.from(changed));
        return raw;
    }
    private static List<Path> entries(Path directory) throws Exception {
        var values = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(directory)) { for (Path path : entries) values.add(path); }
        return values;
    }
}
