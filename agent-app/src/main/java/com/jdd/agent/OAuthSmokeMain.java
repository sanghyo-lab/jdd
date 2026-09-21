package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.infra.*;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit one-call local connectivity check, not a VOC quality evaluation or a Gradle test. */
public final class OAuthSmokeMain {
    public static void main(String[] args) throws Exception {
        if (!"local".equals(System.getenv("APP_RUNTIME")) || !"codex_oauth".equals(System.getenv("LLM_PROVIDER")))
            throw new IllegalStateException("smoke-local requires local/codex_oauth");
        var json = JsonMapper.builder().build();
        var observed = new LinkedHashMap<String, Object>();
        var journal = new OAuthCallJournal() {
            public void start(String id, String investigation, int iteration, String model) { observed.put("callId", id); }
            public void finish(String id, String model, ModelUsage usage, String outcome, long elapsed) {
                observed.put("actualModel", model); observed.put("usage", usage); observed.put("outcome", outcome); observed.put("elapsedMillis", elapsed);
            }
        };
        InvestigationModel model = LlmRuntimeConfiguration.create(System::getenv, null, json, Clock.systemUTC(), journal);
        int exit = 0;
        try {
            var input = new InvestigationInput("1.0", "local-oauth-connectivity", 1, UUID.randomUUID().toString(),
                    "연결 검증용 합성 문의입니다. 주문 번호와 발생 시각을 아직 제공하지 않았습니다.", null, null);
            var reply = model.next(new InvestigationModel.Request("smoke-" + UUID.randomUUID(), input,
                    InvestigationPromptLoader.load(), 1, List.of(), List.of()));
            var report = json.readValue(reply.text(), Investigation.AnalysisReport.class);
            if (!reply.toolCalls().isEmpty() || !"1.0".equals(report.schemaVersion()) || report.missingInformation() == null
                    || report.missingInformation().isEmpty() || report.facts() == null || !report.facts().isEmpty())
                throw new IllegalStateException("Smoke response did not satisfy the missing-input report contract");
            observed.put("validation", "PASS_CONNECTIVITY_ONLY");
            System.out.println(json.writeValueAsString(observed));
        } catch (InvestigationFailure failure) {
            System.err.println(failure.error().code() + ": " + failure.error().message()); exit = 1;
        } catch (RuntimeException failure) {
            System.err.println("Smoke validation failed; no provider response or credential content is logged."); exit = 1;
        } finally { if (model instanceof AutoCloseable closeable) closeable.close(); }
        if (exit != 0) System.exit(exit);
    }
}
