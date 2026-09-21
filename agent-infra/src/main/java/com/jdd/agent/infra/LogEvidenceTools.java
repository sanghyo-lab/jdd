package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation.EvidenceType;
import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationTools.Outcome;
import com.jdd.agent.infra.EvidenceToolArguments.SearchLogs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.jdd.agent.infra.SourceEvidenceTools.*;

/** Bounded JSONL scans; incomplete writes and scan limits are never reported as complete absence. */
public final class LogEvidenceTools {
    private static final int MAX_BUILDS = 32, MAX_FILES = 32, SCAN_BYTES = 4 * 1024 * 1024, LINE_BYTES = 64 * 1024;
    private final Path root;
    private final JsonMapper json;
    private final Clock clock;
    private record Scan(List<Observation> observations, boolean partial, int duplicates, int files) {}
    private record Listing(List<Path> paths, boolean partial) {}

    public LogEvidenceTools(Path root, JsonMapper json, Clock clock) {
        this.root = root.toAbsolutePath().normalize(); this.json = json; this.clock = clock;
    }

    public Outcome search(SearchLogs input) {
        Scan scan = scan(input);
        // Only an empty complete scan is retried, locally, for the committed outbox's short flush delay.
        // This never causes another model call and does not promise that a delayed log cannot arrive later.
        int attempts = 1;
        while (scan.observations().isEmpty() && !scan.partial() && attempts < 3) {
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
            scan = scan(input); attempts++;
        }
        var observations = new ArrayList<>(scan.observations());
        if (scan.partial()) observations.replaceAll(SourceEvidenceTools::partial);
        String scope = input.buildId() == null ? "로컬 보관 build 디렉터리" : "buildId=" + input.buildId();
        return new Outcome(observations, scope + "의 JSONL 상관조건 AND 조회: " + observations.size() + "줄, 파일 " + scan.files()
                + "개, 로컬 조회 " + attempts + "회. 동일 eventId 재출력 " + scan.duplicates() + "줄은 원문을 보존했으며 별도 업무 처리로 세지 마세요. "
                + (scan.partial() ? "검색/결과 한도 또는 미완성 마지막 줄로 일부 결과입니다. 로그가 없다고 단정하지 마세요."
                : "읽은 시점의 완성된 줄을 검색했습니다. outbox 지연 가능성이 있어 빈 결과는 장애 부재를 입증하지 않습니다."));
    }

    private Scan scan(SearchLogs input) {
        Listing builds = input.buildId() == null ? list(root, true, MAX_BUILDS)
                : new Listing(List.of(safePath(root, input.buildId())), false);
        boolean partial = builds.partial();
        int remaining = SCAN_BYTES, files = 0, duplicates = 0;
        var observations = new ArrayList<Observation>();
        var eventIds = new HashMap<String, JsonNode>();
        outer: for (Path directory : builds.paths()) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            String buildId = directory.getFileName().toString();
            if (!buildId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || buildId.contains("..")) throw unavailable();
            Listing logs = list(directory, false, MAX_FILES);
            partial |= logs.partial();
            for (Path path : logs.paths()) {
                if (++files > MAX_FILES || remaining == 0) { partial = true; break outer; }
                byte[] bytes;
                try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(remaining + 1); }
                catch (IOException error) { throw unavailable(); }
                boolean clipped = bytes.length > remaining;
                int readable = Math.min(remaining, bytes.length);
                partial |= clipped;
                remaining -= readable;
                int start = 0, number = 0;
                for (int index = 0; index < readable; index++) {
                    if (index - start >= LINE_BYTES) throw unavailable();
                    if (bytes[index] != '\n') continue;
                    number++;
                    String raw = utf8(java.util.Arrays.copyOfRange(bytes, start, index));
                    start = index + 1;
                    if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
                    if (raw.isBlank()) throw unavailable();
                    JsonNode line = json.readTree(raw);
                    validate(line, buildId);
                    if (!matches(line, input)) continue;
                    if (observations.size() == input.limit()) { partial = true; break outer; }
                    String eventId = line.path("eventId").asText();
                    JsonNode previous = eventId.isBlank() ? null : eventIds.putIfAbsent(eventId, line);
                    if (previous != null && !previous.equals(line)) throw unavailable();
                    boolean duplicate = previous != null;
                    if (duplicate) duplicates++;
                    observations.add(new Observation(EvidenceType.LOG, line.path("event").asText() + " JSONL 원문"
                            + (duplicate ? " (동일 eventId 재출력)" : ""), clock.instant(),
                            Map.of("buildId", buildId, "path", buildId + "/" + path.getFileName(),
                                    "startLine", number, "endLine", number, "eventId", eventId),
                            Map.of("raw", raw, "entry", json.convertValue(line, Map.class)), false));
                }
                if (start < readable) partial = true; // Ignore an uncommitted trailing JSONL line, even if its JSON parses.
                if (clipped) break outer;
            }
        }
        return new Scan(observations, partial, duplicates, files);
    }

    private static Listing list(Path directory, boolean directories, int limit) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw unavailable();
        var result = new ArrayList<Path>();
        boolean partial = false;
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (Files.isSymbolicLink(candidate)) throw unavailable();
                if (directories ? !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        : !candidate.getFileName().toString().endsWith(".jsonl")) continue;
                if (!directories && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
                if (result.size() == limit) { partial = true; break; }
                result.add(safePath(directory, candidate.getFileName().toString()));
            }
        } catch (IOException error) { throw unavailable(); }
        result.sort(Comparator.comparing(Path::toString));
        return new Listing(result, partial);
    }
    private static void validate(JsonNode line, String buildId) {
        if (!line.isObject() || !"1.0".equals(line.path("schemaVersion").asText())
                || !"commerce-app".equals(line.path("service").asText()) || !buildId.equals(line.path("buildId").asText())
                || !line.path("event").isString() || line.path("event").asText().isBlank()
                || !line.path("requestId").isString() || !line.path("details").isObject()
                || !line.path("timestamp").isString()) throw unavailable();
        if (line.has("eventId") && (!line.path("eventId").isString() || line.path("eventId").asText().isBlank())) throw unavailable();
        try { Instant.parse(line.path("timestamp").asText()); }
        catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static boolean matches(JsonNode line, SearchLogs input) {
        if (!equal(line, "requestId", input.requestId()) || !equal(line, "orderId", input.orderId())
                || !equal(line, "productId", input.productId()) || !equal(line, "checkoutKey", input.checkoutKey())) return false;
        if (input.from() == null) return true;
        Instant at = Instant.parse(line.path("timestamp").asText());
        return !at.isBefore(input.from()) && !at.isAfter(input.to());
    }
    private static boolean equal(JsonNode node, String field, String value) {
        return value == null || (node.path(field).isString() && value.equals(node.path(field).asText()));
    }
}
