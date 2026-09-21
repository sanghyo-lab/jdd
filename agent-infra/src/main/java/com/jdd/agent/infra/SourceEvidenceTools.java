package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation.EvidenceType;
import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationTools.Outcome;
import com.jdd.agent.infra.EvidenceToolArguments.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads only hash-checked execution snapshots. Repository files are never a fallback. */
public final class SourceEvidenceTools {
    private static final List<String> ROOTS = List.of("commerce-app/src/main/java/", "commerce-core/src/main/java/",
            "commerce-infra/src/main/java/", "commerce-infra/src/main/resources/db/migration/");
    private static final int FILE_BYTES = 1024 * 1024, SEARCH_BYTES = 4 * 1024 * 1024, SEARCH_FILES = 512;
    private final Path sourceRoot, policy;
    private final JsonMapper json;
    private final Clock clock;
    private record Manifest(Path directory, String buildId, String commit, String policyVersion, Map<String, String> hashes) {}

    public SourceEvidenceTools(Path sourceRoot, Path policy, JsonMapper json, Clock clock) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.policy = policy.toAbsolutePath().normalize();
        this.json = json;
        this.clock = clock;
    }

    public Outcome readCode(ReadCode input) {
        var manifest = manifest(input.buildId());
        String text = source(manifest, input.path());
        List<String> lines = text.lines().toList();
        if (input.startLine() > lines.size()) throw unavailable();
        int end = Math.min(lines.size(), input.endLine());
        return new Outcome(List.of(code(manifest, input.path(), lines, input.startLine(), end, false)),
                "요청한 실행 소스 구간을 manifest 해시와 대조했습니다. 파일 전체가 아니라 지정한 줄 범위입니다.");
    }

    public Outcome searchCode(SearchCode input) {
        var manifest = manifest(input.buildId());
        var observations = new ArrayList<Observation>();
        int scanned = 0, bytes = 0;
        boolean truncated = false;
        outer: for (String path : manifest.hashes().keySet().stream().sorted().toList()) {
            if (!allowed(path)) continue;
            if (++scanned > SEARCH_FILES) { truncated = true; break; }
            String text = source(manifest, path);
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > SEARCH_BYTES) { truncated = true; break; }
            List<String> lines = text.lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).contains(input.query())) continue;
                if (observations.size() == input.limit()) { truncated = true; break outer; }
                int start = Math.max(1, index + 1 - 3), end = Math.min(lines.size(), index + 1 + 3);
                observations.add(code(manifest, path, lines, start, end, false));
            }
        }
        if (truncated) observations.replaceAll(SourceEvidenceTools::partial);
        return new Outcome(observations, "buildId=" + input.buildId() + " 허용된 실행 소스의 리터럴 검색: "
                + observations.size() + "개 구간; " + (truncated ? "검색 한도에 도달해 전체 부재를 판단할 수 없습니다."
                : "manifest의 허용 파일 검색 완료. 일치가 없다는 사실만으로 장애 유무를 판단하지 마세요."));
    }

    public Outcome readPolicy(ReadPolicy input) {
        var manifest = manifest(input.buildId());
        if (!manifest.policyVersion().equals(input.version())) throw unavailable();
        String text = utf8(boundedRead(policy, FILE_BYTES));
        String marker = "적용 버전은 `" + input.version() + "`";
        if (!text.contains(marker)) throw unavailable();
        List<String> lines = text.lines().toList();
        int start = 0, end = lines.size();
        if (input.section() != null) {
            String heading = "## " + input.section();
            start = lines.indexOf(heading);
            if (start < 0) throw unavailable();
            for (int index = start + 1; index < lines.size(); index++) {
                if (lines.get(index).startsWith("## ")) { end = index; break; }
            }
        }
        var source = new LinkedHashMap<String, Object>();
        source.put("path", "docs/business-policy.md"); source.put("version", input.version());
        source.put("section", input.section() == null ? "전체" : input.section());
        source.put("buildId", input.buildId()); source.put("startLine", start + 1); source.put("endLine", end);
        source.put("sha256", sha256(text.getBytes(StandardCharsets.UTF_8)));
        // The current contract pins the version, but does not archive the policy in each manifest.
        source.put("binding", "manifest-version-and-current-policy-hash");
        var observation = new Observation(EvidenceType.POLICY, "정상 업무 정책 " + input.version(), clock.instant(), source,
                String.join("\n", lines.subList(start, end)), false);
        return new Outcome(List.of(observation), "manifest 정책 버전과 현재 정책 원문을 대조했습니다. 저장된 해시는 조회 시점 정책의 해시이며 과거 정책 파일의 보관을 의미하지 않습니다.");
    }

    private Manifest manifest(String buildId) {
        Path directory = safePath(sourceRoot, buildId);
        JsonNode node = json.readTree(boundedRead(safePath(directory, "manifest.json"), FILE_BYTES));
        if (!"1.0".equals(node.path("schemaVersion").asText()) || !buildId.equals(node.path("buildId").asText())
                || !node.path("commitSha").asText().matches("[0-9a-f]{40}") || !node.path("files").isObject()
                || node.path("policyVersion").asText().isBlank()) throw unavailable();
        var hashes = new LinkedHashMap<String, String>();
        node.path("files").properties().forEach(entry -> {
            if (allowed(entry.getKey())) {
                String hash = entry.getValue().asText();
                if (!hash.matches("[0-9a-f]{64}")) throw unavailable();
                hashes.put(entry.getKey(), hash);
            }
        });
        if (hashes.isEmpty()) throw unavailable();
        return new Manifest(directory, buildId, node.path("commitSha").asText(), node.path("policyVersion").asText(), hashes);
    }

    private String source(Manifest manifest, String path) {
        if (!allowed(path) || !manifest.hashes().containsKey(path)) throw unavailable();
        byte[] bytes = boundedRead(safePath(manifest.directory(), path), FILE_BYTES);
        if (!sha256(bytes).equals(manifest.hashes().get(path))) throw unavailable();
        return utf8(bytes);
    }

    private Observation code(Manifest manifest, String path, List<String> lines, int start, int end, boolean truncated) {
        return new Observation(EvidenceType.CODE, "실행 소스 " + path + ":" + start + "-" + end, clock.instant(),
                Map.of("buildId", manifest.buildId(), "commitSha", manifest.commit(), "path", path,
                        "startLine", start, "endLine", end, "sha256", manifest.hashes().get(path)),
                String.join("\n", lines.subList(start - 1, end)), truncated);
    }

    static Observation partial(Observation value) {
        return new Observation(value.type(), value.summary(), value.observedAt(), value.source(), value.content(), true);
    }
    static boolean allowed(String path) {
        return safeName(path) && ROOTS.stream().anyMatch(path::startsWith)
                && (path.endsWith(".java") || path.endsWith(".sql"));
    }
    static boolean safeName(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.codePoints().anyMatch(Character::isISOControl)) return false;
        for (String part : path.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    static Path safePath(Path root, String name) {
        if (!safeName(name)) throw unavailable();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) throw unavailable();
        Path current = root;
        if (Files.isSymbolicLink(current)) throw unavailable();
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw unavailable();
        }
        return target;
    }
    static byte[] boundedRead(Path path, int maxBytes) {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw unavailable();
        try (var stream = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw unavailable();
            return bytes;
        } catch (IOException error) { throw unavailable(); }
    }
    static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw unavailable(); }
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static IllegalStateException unavailable() { return new IllegalStateException("Evidence source is unavailable or invalid"); }
}
