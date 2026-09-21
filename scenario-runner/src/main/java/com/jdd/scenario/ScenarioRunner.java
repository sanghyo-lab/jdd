package com.jdd.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit live HTTP scenario verification. No model credentials, SQL, process execution, or fallback. */
public final class ScenarioRunner {
    static final List<String> CASES = List.of("VOC-01", "VOC-02", "VOC-03", "VOC-04", "VOC-05", "VOC-06",
            "VOC-07", "NORMAL", "NEEDS_INPUT", "IDEMPOTENCY", "RECOVERY");
    private final Path root;
    private final Map<String, String> environment;
    private final Duration observationWindow;
    private final long pollMillis;
    private final Map<String, ObjectNode> results = new LinkedHashMap<>();
    private final Map<String, JsonNode> completed = new LinkedHashMap<>();
    private final Map<String, JsonNode> inputs = new LinkedHashMap<>();
    private final Set<String> observedModels = new HashSet<>();
    private final ObjectNode report = Json.object("schemaVersion", "1.0", "mode", "rejected", "model", null,
            "status", "FAILED", "startedAt", Instant.now().toString());
    private String build, runtime, provider, configuredModel, voc, agent, commerce, runId;
    private Path artifacts;
    private LocalHttp http;
    private EvidenceVerifier evidenceVerifier;
    private JsonNode prepared;

    ScenarioRunner(Path root, Map<String, String> environment, Duration observationWindow, long pollMillis) {
        this.root = root.toAbsolutePath().normalize(); this.environment = Map.copyOf(environment);
        this.observationWindow = observationWindow; this.pollMillis = pollMillis;
        for (String id : CASES) results.put(id, Json.object("id", id, "status", "PENDING", "mocked", null));
        report.set("cases", Json.MAPPER.valueToTree(results.values()));
        report.put("automatedValidation", "Actual HTTP, stored model observations, report references and PostgreSQL/log/source equality; natural-language quality requires independent review.");
    }

    public static void main(String[] args) {
        Path target = null;
        try {
            Json.require(args.length == 2 && args[0].equals("--report"), "Usage: --report <new-result.json>");
            target = Path.of(args[1]).toAbsolutePath().normalize();
            int code = new ScenarioRunner(Path.of("."), System.getenv(), Duration.ofMinutes(14), 1000).run(target);
            System.exit(code);
        } catch (Exception failure) {
            System.err.println("Scenario runner could not preserve its report: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    int run(Path target) throws IOException {
        Json.require(!Files.exists(target), "The report path already exists; prior results are immutable");
        Files.createDirectories(target.getParent());
        artifacts = target.resolveSibling(target.getFileName() + ".artifacts");
        Files.createDirectory(artifacts);
        int exit = 1;
        try (var connection = new LocalHttp(this::recordHttp)) {
            http = connection;
            preflight();
            var runtimeBefore = runtimeObservation();
            Json.write(artifacts.resolve("runtime-before.json"), runtimeBefore);
            for (String id : CASES.subList(0, 9)) {
                ObjectNode result = results.get(id);
                result.put("startedAt", Instant.now().toString());
                try {
                    investigate(id, result);
                    passed(result);
                } catch (Exception failure) {
                    failed(result, failure); throw failure;
                }
            }
            runControl("IDEMPOTENCY", this::idempotency);
            runControl("RECOVERY", this::recovery);
            JsonNode runtimeAfter = runtimeObservation();
            Json.require(runtimeBefore.equals(runtimeAfter), "Runtime changed during scenario execution");
            Json.write(artifacts.resolve("runtime-after.json"), runtimeAfter);
            Json.require(observedModels.size() == 1, "Scenarios must have one unambiguous observed model");
            report.put("model", observedModels.iterator().next());
            report.put("status", "PASSED");
            exit = 0;
        } catch (Exception failure) {
            report.put("error", safeFailure(failure));
        } finally {
            report.put("finishedAt", Instant.now().toString());
            report.set("cases", Json.MAPPER.valueToTree(results.values()));
            Json.write(target, report);
        }
        System.out.println("Scenario verification " + report.path("status").asText() + ": " + target);
        return exit;
    }

    private void preflight() throws IOException {
        Json.require("true".equals(environment.get("JDD_MVP_LIVE")), "Explicit JDD_MVP_LIVE=true is required");
        runtime = environment.get("APP_RUNTIME"); provider = environment.get("LLM_PROVIDER");
        Json.require(("local".equals(runtime) && "codex_oauth".equals(provider))
                || ("deployed".equals(runtime) && "openai_api".equals(provider)), "Mock/unselected provider cannot verify live scenarios");
        configuredModel = environment.get("local".equals(runtime) ? "CODEX_MODEL" : "OPENAI_MODEL");
        Json.require(configuredModel != null && configuredModel.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && !configuredModel.startsWith("replace-") && !configuredModel.equalsIgnoreCase("mock"), "An authorized model selection is required");
        String manifestPath = environment.get("JDD_SCENARIO_MANIFEST"), checksum = environment.get("JDD_SCENARIO_MANIFEST_SHA256");
        Json.require(manifestPath != null && checksum != null && checksum.matches("[0-9a-f]{64}"), "Prepared case manifest and checksum are required");
        Path path = Path.of(manifestPath).toAbsolutePath().normalize();
        prepared = Json.read(path);
        Json.require(Json.sha(Files.readAllBytes(path)).equals(checksum), "Prepared case manifest checksum mismatch");
        Json.require(prepared.path("schemaVersion").asText().equals("1.0")
                && prepared.path("mode").asText().equals("actual-http-postgresql")
                && prepared.path("modelCalls").isIntegralNumber() && prepared.path("modelCalls").asInt() == 0,
                "Live cases require actual model-free HTTP/PostgreSQL preparation");
        runId = Json.text(prepared, "runId"); UUID.fromString(runId);
        build = Json.text(prepared, "buildId");
        Json.require(build.matches("[a-f0-9]{12}-[a-f0-9]{12}"), "Invalid prepared build ID");
        Instant.parse(Json.text(prepared, "preparedAt"));
        for (JsonNode item : Json.list(prepared, "cases")) {
            String id = Json.text(item, "id");
            Json.require(CASES.subList(0, 9).contains(id) && inputs.put(id, item) == null,
                    "Unexpected or duplicate prepared case");
            Json.text(item, "message");
            Json.require(item.path("context").isObject() && item.path("database").isObject()
                    && item.path("preparation").path("status").asText().equals("PASSED"), "Unverified case preparation");
            for (var field : item.path("context").properties()) Json.require(Set.of("customerId", "orderId", "productId",
                    "requestId", "checkoutKey", "occurredAt").contains(field.getKey()), "Evaluation metadata in investigation context");
        }
        Json.require(inputs.size() == 9, "All nine investigation inputs are required");
        evidenceVerifier = new EvidenceVerifier(root, build, prepared.path("sourceManifest"));
        voc = base("VOC_PORT", 8082); agent = base("AGENT_PORT", 8081); commerce = base("COMMERCE_PORT", 8080);
        Json.require(environment.get("JDD_SCENARIO_COORDINATOR") != null
                && environment.get("JDD_SCENARIO_CAPABILITY") != null, "A live one-use recovery coordinator is required");
        report.put("mode", "live"); report.put("buildId", build); report.put("runId", runId);
        report.put("runtime", runtime); report.put("provider", provider);
        report.put("preparedManifestSha256", checksum);
    }

    private String base(String field, int fallback) {
        String value = environment.getOrDefault(field, String.valueOf(fallback));
        Json.require(value.matches("[0-9]{1,5}") && Integer.parseInt(value) > 0 && Integer.parseInt(value) <= 65535,
                "Invalid service port");
        return "http://127.0.0.1:" + value;
    }
    private ObjectNode runtimeObservation() {
        var result = Json.object();
        for (var service : Map.of("commerce", commerce, "agent", agent, "voc", voc).entrySet()) {
            JsonNode observed = http.expect(service.getValue(), "GET", "/internal/runtime", null, 200);
            Json.require(observed.path("buildId").asText().equals(build)
                    && observed.path("commitSha").equals(prepared.path("sourceManifest").path("commitSha"))
                    && observed.path("businessReady").asBoolean(), "Current-build business readiness missing: " + service.getKey());
            if (service.getKey().equals("agent")) {
                Json.require(observed.path("workerEnabled").asBoolean()
                        && observed.path("investigationModel").asText().equals("local".equals(runtime) ? "CODEX_OAUTH" : "OPENAI")
                        && observed.path("llm").path("runtime").asText().equals(runtime)
                        && observed.path("llm").path("provider").asText().equals(provider)
                        && observed.path("llm").path("configuredModel").asText().equals(configuredModel), "Prepared Agent selection/worker mismatch");
            }
            result.set(service.getKey(), observed);
        }
        return result;
    }

    private void investigate(String id, ObjectNode result) throws IOException, InterruptedException {
        runtimeObservation(); // A changed provider/build stops before another investigation is created.
        JsonNode input = inputs.get(id);
        JsonNode ticket = http.expect(voc, "POST", "/api/tickets", Json.object("title", "합성 조사 " + runId,
                "message", input.path("message"), "context", input.path("context"), "assigneeId", "sanghyo"), 201);
        String ticketId = identifier(ticket, "ticketId");
        String key = "scenario-" + UUID.randomUUID();
        ObjectNode body = Json.object("requestKey", key, "ticketVersion", 1, "previousInvestigationId", null);
        JsonNode accepted = http.expect(voc, "POST", "/api/tickets/" + ticketId + "/analyses", body, 202);
        String analysisId = identifier(accepted, "analysisRequestId");
        result.put("ticketId", ticketId); result.put("analysisRequestId", analysisId);
        result.put("requestKey", key); result.put("ticketVersion", 1); result.put("buildId", build);
        String path = "/api/tickets/" + ticketId + "/analyses/" + analysisId;
        Instant deadline = Instant.now().plus(observationWindow);
        JsonNode view = accepted;
        while (true) {
            Json.require(view.path("ticketId").asText().equals(ticketId) && view.path("ticketVersion").asInt() == 1,
                    "Analysis ownership/version changed");
            if (view.path("submissionStatus").asText().equals("FAILED")) {
                Json.write(artifacts.resolve(id + "-failed-delivery.json"), view);
                throw new Json.VerificationFailure("Analysis delivery failed: " + view.path("submissionError").path("code").asText());
            }
            String status = view.path("investigation").path("status").asText();
            if (Set.of("COMPLETED", "NEEDS_INPUT", "FAILED").contains(status)) break;
            if (Instant.now().isAfter(deadline)) {
                Json.write(artifacts.resolve(id + "-observation-timeout.json"), view);
                throw new Json.VerificationFailure("Observation deadline exceeded without starting a replacement investigation");
            }
            Thread.sleep(pollMillis);
            view = http.expect(voc, "GET", path, null, 200);
        }
        Json.write(artifacts.resolve(id + "-analysis.json"), view);
        String investigationId = identifier(view, "investigationId");
        result.put("investigationId", investigationId);
        JsonNode investigation = http.expect(agent, "GET", "/api/investigations/" + investigationId, null, 200);
        Json.write(artifacts.resolve(id + "-investigation.json"), investigation);
        Json.require(investigation.equals(view.path("investigation")), "VOC cached investigation differs from Agent source");
        Json.require(investigation.path("ticketId").asText().equals(ticketId) && investigation.path("ticketVersion").asInt() == 1,
                "Investigation ownership mismatch");
        JsonNode observations = modelObservations(investigationId);
        Json.write(artifacts.resolve(id + "-model-observations.json"), observations);
        String model = validateModel(observations, investigationId);
        result.put("model", model); result.put("mocked", false);
        Json.require(view.path("submissionStatus").asText().equals("SUBMITTED") && view.path("submissionError").isNull()
                && view.path("syncError").isNull() && view.path("lastSyncedAt").isString(), "Terminal analysis transport state is invalid");
        Json.require(investigation.path("status").asText().equals(id.equals("NEEDS_INPUT") ? "NEEDS_INPUT" : "COMPLETED"),
                "Unexpected investigation outcome: " + investigation.path("status").asText() + "/" + investigation.path("error").path("code").asText());
        var evidence = new LinkedHashMap<String, JsonNode>();
        for (JsonNode summary : Json.list(investigation, "evidence")) {
            String evidenceId = identifier(summary, "evidenceId");
            Json.require(!evidence.containsKey(evidenceId), "Duplicate evidence ID");
            JsonNode detail = http.expect(voc, "GET", path + "/evidence/" + evidenceId, null, 200);
            JsonNode original = http.expect(agent, "GET", "/api/investigations/" + investigationId + "/evidence/" + evidenceId, null, 200);
            Json.require(detail.equals(original), "VOC evidence differs from the Agent's saved observation");
            for (var field : summary.properties()) Json.require(field.getValue().equals(detail.get(field.getKey())), "Evidence summary/detail mismatch");
            evidenceVerifier.verify(detail, input);
            evidence.put(evidenceId, detail);
        }
        Json.write(artifacts.resolve(id + "-evidence.json"), Json.MAPPER.valueToTree(evidence.values()));
        ReportChecks.verify(id, investigation, evidence);
        var refreshed = http.expect(voc, "GET", "/api/tickets/" + ticketId, null, 200);
        Json.require(refreshed.path("ticket").path("status").asText().equals("OPEN"), "Investigation completion changed ticket business status");
        result.set("evidenceIds", Json.MAPPER.valueToTree(evidence.keySet()));
        result.put("evidenceCount", evidence.size());
        result.put("reportArtifact", artifacts.getFileName() + "/" + id + "-investigation.json");
        result.put("semanticReview", "REQUIRED: independent review of the saved original report");
        completed.put(id, view);
    }

    private JsonNode modelObservations(String investigation) {
        return http.expect(agent, "GET", "/internal/investigations/" + investigation + "/model-observations", null, 200);
    }
    private String validateModel(JsonNode observations, String investigation) {
        Json.require(observations.path("schemaVersion").asText().equals("1.0")
                && observations.path("investigationId").asText().equals(investigation)
                && observations.path("runtime").asText().equals(runtime)
                && observations.path("provider").asText().equals(provider), "Model observation ownership/provider mismatch");
        ArrayNode calls = Json.list(observations, "calls");
        Json.require(!calls.isEmpty(), "No actual model calls were observed");
        Set<String> callIds = new HashSet<>();
        String model = null;
        JsonNode finalCall = null;
        for (JsonNode call : calls) {
            Json.require(callIds.add(Json.text(call, "callId")) && call.path("investigationId").asText().equals(investigation)
                    && call.path("provider").asText().equals(provider), "Model call identity/provider mismatch");
            Json.text(call, "outcome");
            Json.require(call.has("actualModel"), "Unobserved response model must remain explicit null");
            if (call.path("actualModel").isString()) {
                model = call.path("actualModel").asText();
                Json.require(!model.isBlank() && !model.equalsIgnoreCase("mock"), "A real response model is required");
                observedModels.add(model);
            }
            Json.require(call.has("usage"), "Model usage must explicitly preserve unknown/null");
            finalCall = call;
        }
        String outcome = finalCall.path("outcome").asText();
        Json.require(finalCall.path("actualModel").isString() && outcome.startsWith("HTTP_200")
                && !outcome.contains("failed") && !outcome.contains("incomplete"), "The final model response was not confirmed");
        model = finalCall.path("actualModel").asText();
        return model;
    }

    private void idempotency(ObjectNode result) throws IOException {
        JsonNode original = completed.get("NORMAL");
        String ticket = identifier(original, "ticketId"), analysis = identifier(original, "analysisRequestId"),
                investigation = identifier(original, "investigationId");
        String path = "/api/tickets/" + ticket + "/analyses";
        ObjectNode body = Json.object("requestKey", original.path("input").path("requestKey"), "ticketVersion", 1,
                "previousInvestigationId", null);
        JsonNode ledger = modelObservations(investigation);
        Json.require(http.expect(voc, "POST", path, body, 202).equals(original), "Same-key replay changed saved analysis");
        JsonNode changed = http.expect(voc, "PATCH", "/api/tickets/" + ticket,
                Json.object("expectedVersion", 1, "title", "합성 조사 보완 " + runId), 200);
        Json.require(changed.path("version").asInt() == 2, "Ticket version did not advance");
        Json.require(http.expect(voc, "POST", path, body, 202).equals(original), "Old same key lost the original input snapshot");
        JsonNode conflict = http.expect(voc, "POST", path, Json.object("requestKey", body.path("requestKey"), "ticketVersion", 2), 409);
        Json.require(conflict.path("code").asText().equals("REQUEST_KEY_CONFLICT"), "Same-key mismatch was not rejected");
        JsonNode stale = http.expect(voc, "POST", path, Json.object("requestKey", "unused-" + UUID.randomUUID(), "ticketVersion", 1), 409);
        Json.require(stale.path("code").asText().equals("TICKET_VERSION_CONFLICT"), "Stale new request was not rejected");
        JsonNode detail = http.expect(voc, "GET", "/api/tickets/" + ticket, null, 200);
        Json.require(detail.path("analyses").size() == 1 && detail.path("ticket").path("status").asText().equals("OPEN"),
                "Replay/conflict created another analysis or changed ticket status");
        Json.require(modelObservations(investigation).equals(ledger), "Replay unexpectedly changed model call ledger");
        // An unrelated synthetic ticket cannot read this analysis or any of its evidence.
        String otherTicket = identifier(completed.get("NEEDS_INPUT"), "ticketId");
        http.expect(voc, "GET", "/api/tickets/" + otherTicket + "/analyses/" + analysis, null, 404);
        JsonNode firstEvidence = original.path("investigation").path("evidence").get(0);
        Json.require(firstEvidence != null, "Normal control needs saved evidence");
        http.expect(voc, "GET", "/api/tickets/" + otherTicket + "/analyses/" + analysis + "/evidence/"
                + identifier(firstEvidence, "evidenceId"), null, 404);
        result.put("ticketId", ticket); result.put("analysisRequestId", analysis); result.put("investigationId", investigation);
        result.put("model", results.get("NORMAL").path("model").asText()); result.put("mocked", false);
        result.put("additionalModelCalls", 0); result.put("snapshotPreservedAfterTicketEdit", true);
        Json.write(artifacts.resolve("IDEMPOTENCY-ticket.json"), detail);
    }

    private void recovery(ObjectNode result) throws IOException {
        JsonNode original = completed.get("VOC-07");
        String ticket = identifier(original, "ticketId"), analysis = identifier(original, "analysisRequestId"),
                investigation = identifier(original, "investigationId");
        String path = "/api/tickets/" + ticket + "/analyses/" + analysis;
        JsonNode before = http.expect(voc, "GET", "/api/tickets/" + ticket, null, 200);
        JsonNode ledger = modelObservations(investigation);
        JsonNode evidence = Json.read(artifacts.resolve("VOC-07-evidence.json"));
        LocalHttp.Response response = http.request(environment.get("JDD_SCENARIO_COORDINATOR"), "POST", "/restart-voc",
                Json.object("runId", runId), Map.of("X-Jdd-Scenario-Capability", environment.get("JDD_SCENARIO_CAPABILITY")), 90);
        Json.write(artifacts.resolve("RECOVERY-process.json"), response.body());
        JsonNode receipt = response.body();
        Json.require(response.status() == 200 && receipt.path("status").asText().equals("PASSED")
                && receipt.path("runId").asText().equals(runId) && receipt.path("buildId").asText().equals(build)
                && receipt.path("unavailableObserved").asBoolean()
                && !receipt.path("before").path("startedAt").equals(receipt.path("after").path("startedAt"))
                && receipt.path("before").path("containerId").equals(receipt.path("after").path("containerId")),
                "Actual process restart/unavailability was not verified");
        Json.require(http.expect(voc, "GET", "/api/tickets/" + ticket, null, 200).equals(before), "Ticket/history changed across restart");
        Json.require(http.expect(voc, "GET", path, null, 200).equals(original), "Analysis snapshot changed across restart");
        for (JsonNode item : evidence) Json.require(http.expect(voc, "GET", path + "/evidence/" + identifier(item, "evidenceId"), null, 200)
                .equals(item), "Saved evidence changed across restart");
        Json.require(modelObservations(investigation).equals(ledger), "Restart caused a replacement model call");
        result.put("ticketId", ticket); result.put("analysisRequestId", analysis); result.put("investigationId", investigation);
        result.put("model", results.get("VOC-07").path("model").asText()); result.put("mocked", false);
        result.put("additionalModelCalls", 0); result.put("actualContainerRestart", true);
    }

    private interface Control { void run(ObjectNode result) throws Exception; }
    private void runControl(String id, Control control) throws Exception {
        ObjectNode result = results.get(id); result.put("startedAt", Instant.now().toString());
        try { control.run(result); passed(result); }
        catch (Exception failure) { failed(result, failure); throw failure; }
    }
    private static void passed(ObjectNode result) {
        result.put("status", "PASSED"); result.put("finishedAt", Instant.now().toString());
    }
    private static void failed(ObjectNode result, Exception failure) {
        result.put("status", "FAILED"); result.put("error", safeFailure(failure));
        result.put("finishedAt", Instant.now().toString());
    }
    private static String safeFailure(Exception failure) {
        return failure instanceof Json.VerificationFailure ? failure.getMessage() : failure.getClass().getSimpleName();
    }
    private static String identifier(JsonNode object, String field) {
        String id = Json.text(object, field);
        Json.require(id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "Unsafe HTTP identifier");
        return id;
    }
    private void recordHttp(JsonNode record) {
        try {
            Files.writeString(artifacts.resolve("http-observations.jsonl"), Json.MAPPER.writeValueAsString(record) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { throw new Json.VerificationFailure("HTTP trace could not be preserved"); }
    }
}
