package com.jdd.voc.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

public final class AnalysisProcessor {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "NEEDS_INPUT", "FAILED");
    public record Settings(Duration lease, Duration pollInterval, Duration observationWindow,
                           int deliveryAttempts, int queueRetries) {
        public Settings {
            if (lease.isNegative() || lease.isZero() || pollInterval.isNegative() || pollInterval.isZero()
                    || observationWindow.compareTo(pollInterval) < 0 || deliveryAttempts < 1 || deliveryAttempts > 3
                    || queueRetries < 0 || queueRetries > 3)
                throw new IllegalArgumentException("Invalid VOC worker limits");
        }
    }
    private final AnalysisWorkRepository store;
    private final AgentGateway agent;
    private final Clock clock;
    private final Settings settings;
    private final LongSupplier jitterMillis;

    public AnalysisProcessor(AnalysisWorkRepository store, AgentGateway agent, Clock clock, Settings settings,
                             LongSupplier jitterMillis) {
        this.store = store; this.agent = agent; this.clock = clock; this.settings = settings; this.jitterMillis = jitterMillis;
    }

    public boolean processNext() {
        var claimed = store.claim(now(), settings.lease());
        if (claimed.isEmpty()) return false;
        var work = claimed.get();
        if (work.analysis().submissionStatus() == AnalysisRequest.SubmissionStatus.PENDING) deliver(work);
        else poll(work);
        return true;
    }

    private void deliver(AnalysisWorkRepository.Work work) {
        var previous = work.analysis().submissionError();
        int allowed = previous != null && previous.code().equals("INVESTIGATION_QUEUE_FULL")
                ? settings.queueRetries() + 1 : settings.deliveryAttempts();
        // A process may disappear after sending but before saving a response. Count that
        // attempt too, then let the user explicitly replay the same key after exhaustion.
        if (work.attempts() > allowed) {
            store.deliveryFailed(work, previous == null ? unavailable() : previous, null, work.queueRejections(), now());
            return;
        }
        try {
            var accepted = agent.submit(work.analysis().input());
            Instant received = now();
            store.submitted(work, accepted.investigationId(), received, received.plus(settings.observationWindow()));
        } catch (AgentGateway.Failure failure) {
            boolean queue = failure.status() == 429 && failure.error().code().equals("INVESTIGATION_QUEUE_FULL");
            int rejections = work.queueRejections() + (queue ? 1 : 0);
            Instant received = now();
            Instant next = null;
            if (failure.error().retryable()) {
                if (queue && rejections <= settings.queueRetries() && work.attempts() <= settings.queueRetries()) {
                    Duration delay = Duration.ofSeconds(5L << (rejections - 1));
                    if (failure.retryAfter().compareTo(delay) > 0) delay = failure.retryAfter();
                    next = received.plus(delay).plusMillis(Math.clamp(jitterMillis.getAsLong(), 0, 1000));
                } else if (!queue && work.attempts() < settings.deliveryAttempts()) {
                    next = received.plusSeconds(work.attempts());
                }
            }
            store.deliveryFailed(work, failure.error(), next, rejections, received);
        }
    }

    private void poll(AnalysisWorkRepository.Work work) {
        Instant start = now();
        if (terminal(work.analysis().investigation())) {
            store.polled(work, null, work.analysis().syncError(), null, start);
            return;
        }
        if (!work.manualPoll() && !start.isBefore(work.observeUntil())) {
            store.polled(work, null, observationExpired(), null, start);
            return;
        }
        try {
            Map<String, Object> investigation = agent.investigation(work.analysis());
            Instant received = now();
            Instant next = terminal(investigation) ? null : nextPoll(received, work.observeUntil());
            store.polled(work, investigation, !terminal(investigation) && next == null ? observationExpired() : null, next, received);
        } catch (AgentGateway.Failure failure) {
            Instant received = now();
            store.polled(work, null, failure.error(), failure.error().retryable()
                    ? nextPoll(received, work.observeUntil()) : null, received);
        }
    }

    private Instant nextPoll(Instant now, Instant deadline) {
        if (!now.isBefore(deadline)) return null;
        Instant next = now.plus(settings.pollInterval());
        return next.isBefore(deadline) ? next : deadline;
    }

    public static boolean terminal(Map<String, Object> investigation) {
        return investigation != null && TERMINAL.contains(investigation.get("status"));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static AnalysisRequest.Error unavailable() {
        return new AnalysisRequest.Error("AGENT_UNAVAILABLE", "접수 응답을 확인하지 못했습니다. 같은 키로 다시 전달할 수 있습니다.", true);
    }
    private static AnalysisRequest.Error observationExpired() {
        return new AnalysisRequest.Error("AGENT_OBSERVATION_EXPIRED",
                "자동 상태 확인 시간이 끝났습니다. 새로고침으로 기존 조사를 다시 확인해 주세요.", true);
    }
}
