package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.ApiError;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.*;
import tools.jackson.databind.json.JsonMapper;

/** Local-only Codex backend adapter. It has no API key, paid ledger, subprocess, or alternative endpoint. */
public final class CodexOAuthInvestigationModel implements InvestigationModel, AutoCloseable {
    private final Path authFile;
    private final String model;
    private final JsonMapper json;
    private final Clock clock;
    private final ResponsesHttp network;
    private final ResponsesProtocol protocol;
    private final URI endpoint;
    private final OAuthCallJournal journal;
    public CodexOAuthInvestigationModel(Path authFile, String model, JsonMapper json, Clock clock) {
        this(authFile, model, json, clock, OAuthCallJournal.NONE);
    }
    public CodexOAuthInvestigationModel(Path authFile, String model, JsonMapper json, Clock clock, OAuthCallJournal journal) {
        // A real tool investigation reached the former 45s cap while producing its final report.
        // Keep a bounded call and the worker's independent 3-minute investigation cancellation.
        this(authFile, model, json, clock, URI.create("https://chatgpt.com/backend-api/codex/responses"), Duration.ofSeconds(90), journal);
    }
    public static CodexOAuthInvestigationModel localMock(Path authFile, String model, JsonMapper json, Clock clock, URI endpoint, Duration timeout) {
        return new CodexOAuthInvestigationModel(authFile, model, json, clock, ResponsesHttp.loopback(endpoint), timeout, OAuthCallJournal.NONE);
    }
    private CodexOAuthInvestigationModel(Path authFile, String model, JsonMapper json, Clock clock, URI endpoint, Duration timeout, OAuthCallJournal journal) {
        this.authFile = authFile; this.model = model; this.json = json; this.clock = clock; this.endpoint = endpoint;
        this.journal = journal;
        this.network = new ResponsesHttp(json, Duration.ofSeconds(3), timeout); this.protocol = new ResponsesProtocol(json);
    }
    @Override public Mode mode() { return Mode.CODEX_OAUTH; }
    @Override public void close() { network.close(); }
    @Override public Reply next(Request request) {
        String body = json.writeValueAsString(protocol.payload(request, model));
        if (body.getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
            throw new InvestigationFailure(new ApiError("INVESTIGATION_BUDGET_EXCEEDED", "조사 모델 입력 한도를 초과했습니다.", false));
        // Codex's request schema has no max_output_tokens. Do not guess API-only controls.
        // Existing runner call/time/tool limits and bounded SSE size also apply locally.
        var headers = CodexOAuthCredentials.headers(authFile, json, clock);
        String callId = java.util.UUID.randomUUID().toString(); long started = System.nanoTime();
        journal.start(callId, request.investigationId(), request.iteration(), model);
        final ResponsesHttp.Received received;
        try { received = network.post(endpoint, headers, body, true); }
        catch (RuntimeException failure) {
            journal.finish(callId, null, null, Thread.currentThread().isInterrupted() ? "CANCELLED_USAGE_UNKNOWN" : "TRANSPORT_FAILURE_USAGE_UNKNOWN",
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            if (Thread.currentThread().isInterrupted()) throw ResponsesHttp.cancelled();
            throw InvestigationFailure.unavailable();
        }
        journal.finish(callId, received.response() == null ? null : received.response().path("model").asText(null),
                received.response() == null ? null : OpenAiUsageParser.parse(received.response()),
                "HTTP_" + received.status() + "_" + (received.response() == null ? "USAGE_UNKNOWN" : received.response().path("status").asText("FAILED")),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        if (received.status() == 401 || received.status() == 403) throw CodexOAuthCredentials.loginRequired();
        if (received.status() == 429) throw new InvestigationFailure(new ApiError("LLM_UNAVAILABLE",
                "로컬 Codex 사용량 제한입니다. 워크스페이스 한도를 확인한 뒤 다시 조사하세요.", true));
        if (received.status() >= 300 && received.status() < 500 && received.status() != 408)
            throw new InvestigationFailure(new ApiError("LLM_CONFIGURATION_ERROR", "CODEX_MODEL 접근 권한과 Codex 요청 호환성을 확인해 주세요.", false));
        if (received.status() != 200 || received.response() == null) throw InvestigationFailure.unavailable();
        return protocol.reply(received.response());
    }
}
