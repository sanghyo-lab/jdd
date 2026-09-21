package com.jdd.agent;

import com.jdd.agent.domain.InvestigationExecutionRepository;
import com.jdd.agent.domain.Investigation.ApiError;
import com.jdd.agent.domain.InvestigationRunner;
import com.jdd.agent.domain.ModelCallLedger;
import com.jdd.agent.infra.JdbcWorkerOwnership;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jdd.agent.worker.enabled", havingValue = "true", matchIfMissing = true)
public final class InvestigationWorker {
    private static final Logger log = LoggerFactory.getLogger(InvestigationWorker.class);
    private final InvestigationExecutionRepository executions;
    private final InvestigationRunner runner;
    private final ModelCallLedger ledger;
    private final JdbcWorkerOwnership ownership;
    private final int concurrency;
    private final Duration maximumRuntime;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("investigation-dispatch").factory());
    private final Map<String, Running> active = new HashMap<>();
    private boolean ownerReady;
    private boolean closed;
    private record Running(Future<?> future, AtomicBoolean finished, InvestigationExecutionRepository.Claim claim) {}

    public InvestigationWorker(InvestigationExecutionRepository executions, InvestigationRunner runner,
                               ModelCallLedger ledger, JdbcWorkerOwnership ownership,
                               @Value("${jdd.agent.worker.concurrency:2}") int concurrency,
                               @Value("${jdd.agent.worker.maximum-runtime:PT3M}") Duration maximumRuntime) {
        if (concurrency < 1 || concurrency > 4 || maximumRuntime.isNegative() || maximumRuntime.isZero())
            throw new IllegalArgumentException("Invalid worker limits");
        this.executions = executions;
        this.runner = runner;
        this.ledger = ledger;
        this.ownership = ownership;
        this.concurrency = concurrency;
        this.maximumRuntime = maximumRuntime;
        workers = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().name("investigation-", 0).factory());
    }

    @EventListener(ApplicationReadyEvent.class) public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    }

    /** Ownership and startup recovery completed; not a model authentication or quality assertion. */
    public synchronized boolean isReady() { return ownerReady && !closed; }

    private synchronized void tick() {
        if (closed) return;
        try {
            if (!ownerReady) {
                if (!ownership.acquire()) return;
                ledger.recoverUnsettled(Instant.now());
                int interrupted = executions.recoverInterrupted(Instant.now());
                ownerReady = true;
                log.info("Investigation worker acquired ownership; interrupted investigations={}", interrupted);
            }
            if (!ownership.valid()) throw new IllegalStateException("Worker ownership connection was lost");
            Instant now = Instant.now();
            // Waiting deadlines also advance while every running slot is occupied.
            executions.expireQueued(now);
            executions.expire(now);
            active.values().forEach(task -> { if (!now.isBefore(task.claim().deadline())) task.future().cancel(true); });
            active.values().removeIf(task -> task.finished().get());
            while (active.size() < concurrency) {
                var next = executions.claimNext(Instant.now(), maximumRuntime);
                if (next.isEmpty()) break;
                var claim = next.get();
                var finished = new AtomicBoolean();
                Future<?> future = workers.submit(() -> {
                    try { runner.run(claim); }
                    catch (RuntimeException failedToPersist) { log.error("Investigation persistence failure: {}", failedToPersist.getClass().getSimpleName()); }
                    finally { finished.set(true); }
                });
                active.put(claim.investigationId(), new Running(future, finished, claim));
            }
        } catch (Exception unavailable) {
            log.warn("Investigation dispatch unavailable: {}", unavailable.getClass().getSimpleName());
            interruptActive();
            ownership.close();
            ownerReady = false;
        }
    }

    @PreDestroy public synchronized void stop() {
        closed = true;
        scheduler.shutdownNow();
        interruptActive();
        workers.shutdownNow();
        ownership.close();
        // The next exclusive owner records any remaining RUNNING executions as INTERRUPTED.
    }

    private void interruptActive() {
        active.values().forEach(task -> {
            try {
                executions.fail(task.claim(), new ApiError("INTERRUPTED", "Agent 실행이 중단되었습니다.", false), Instant.now());
            } catch (RuntimeException unavailable) {
                log.warn("Interrupted investigation awaits startup recovery: {}", unavailable.getClass().getSimpleName());
            } finally {
                task.future().cancel(true);
            }
        });
    }
}
