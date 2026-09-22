package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation.EvidenceType;
import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationTools.Outcome;
import com.jdd.agent.infra.EvidenceToolArguments.SearchLogs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    private static final int DISCOVERY_ENTRIES = 4096;
    private final Path root;
    private final JsonMapper json;
    private final Clock clock;
    private record Scan(List<Observation> observations, boolean partial, int duplicates, int files) {}
    private record LogFile(Path path, FileTime modified) {}
    private record LogBuild(String buildId, List<LogFile> files) {}
    private record Discovery(List<LogBuild> builds, boolean partial) {}
    private static final Comparator<LogFile> RECENT_FILE = Comparator.comparing(LogFile::modified).reversed()
            .thenComparing(file -> file.path().toString());
    private static final class DiscoveryBudget {
        int remaining = DISCOVERY_ENTRIES;
        boolean partial;
        boolean take() {
            if (remaining == 0) { partial = true; return false; }
            remaining--;
            return true;
        }
    }

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
        String traceScope = input.orderId() == null ? ""
                : " orderId 조건은 주문 생성 전 로그를 제외할 수 있습니다. 요청 전체 흐름이 필요하면 확인한 requestId 또는 checkoutKey로 조회하고 orderId는 null로 두세요. 다른 조건도 AND로 적용됩니다.";
        return new Outcome(observations, scope + "의 JSONL 상관조건 AND 조회 (로그 파일 수정 시각 내림차순으로 빌드·파일 선택): " + observations.size() + "줄, 파일 " + scan.files()
                + "개, 로컬 조회 " + attempts + "회. 동일 eventId 재출력 " + scan.duplicates() + "줄은 원문을 보존했으며 별도 업무 처리로 세지 마세요. "
                + (scan.partial() ? "검색/결과 한도 또는 미완성 마지막 줄로 일부 결과입니다. 로그가 없다고 단정하지 마세요."
                : "읽은 시점의 완성된 줄을 검색했습니다. outbox 지연 가능성이 있어 빈 결과는 장애 부재를 입증하지 않습니다.") + traceScope);
    }

    private Scan scan(SearchLogs input) {
        Discovery builds = discover(input.buildId());
        boolean partial = builds.partial();
        int remaining = SCAN_BYTES, files = 0, duplicates = 0;
        var observations = new ArrayList<Observation>();
        var eventIds = new HashMap<String, JsonNode>();
        outer: for (LogBuild build : builds.builds()) {
            String buildId = build.buildId();
            for (LogFile file : build.files()) {
                Path path = file.path();
                if (files == MAX_FILES || remaining == 0) { partial = true; break outer; }
                files++;
                byte[] bytes;
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(remaining + 1); }
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

    private Discovery discover(String buildId) {
        var budget = new DiscoveryBudget();
        var builds = new ArrayList<LogBuild>();
        if (buildId != null) addBuild(safePath(root, buildId), builds, budget);
        else {
            requireDirectory(root);
            try (var entries = Files.newDirectoryStream(root)) {
                for (Path candidate : entries) {
                    if (!budget.take()) break;
                    if (Files.isSymbolicLink(candidate)) throw unavailable();
                    if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                        addBuild(safePath(root, candidate.getFileName().toString()), builds, budget);
                }
            } catch (IOException error) { throw unavailable(); }
        }
        builds.sort(Comparator.comparing((LogBuild build) -> build.files().getFirst().modified()).reversed()
                .thenComparing(LogBuild::buildId));
        if (builds.size() > MAX_BUILDS) budget.partial = true;
        return new Discovery(List.copyOf(builds.subList(0, Math.min(MAX_BUILDS, builds.size()))), budget.partial);
    }

    private static void addBuild(Path directory, List<LogBuild> builds, DiscoveryBudget budget) {
        requireDirectory(directory);
        String buildId = directory.getFileName().toString();
        if (!buildId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || buildId.contains("..")) throw unavailable();
        var files = new ArrayList<LogFile>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (!budget.take()) break;
                if (Files.isSymbolicLink(candidate)) throw unavailable();
                if (!candidate.getFileName().toString().endsWith(".jsonl")) continue;
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
                Path path = safePath(directory, candidate.getFileName().toString());
                files.add(new LogFile(path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)));
            }
        } catch (IOException error) { throw unavailable(); }
        files.sort(RECENT_FILE);
        if (files.size() > MAX_FILES) budget.partial = true;
        if (!files.isEmpty()) builds.add(new LogBuild(buildId, List.copyOf(files.subList(0, Math.min(MAX_FILES, files.size())))));
    }
    private static void requireDirectory(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw unavailable();
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
