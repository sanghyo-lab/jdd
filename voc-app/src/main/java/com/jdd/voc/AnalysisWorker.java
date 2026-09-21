package com.jdd.voc;

import com.jdd.voc.domain.AnalysisProcessor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** No request-thread task or in-memory queue is needed for recovery. */
public final class AnalysisWorker implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final Semaphore slots;

    public AnalysisWorker(AnalysisProcessor processor, int concurrency, long tickMillis) {
        if (concurrency < 1 || concurrency > 8 || tickMillis < 10 || tickMillis > 5000)
            throw new IllegalArgumentException("Invalid VOC worker concurrency or tick interval");
        slots = new Semaphore(concurrency);
        workers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().daemon().name("voc-analysis-", 0).factory());
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("voc-analysis-scheduler").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            for (int i = 0; i < concurrency && slots.tryAcquire(); i++) {
                try {
                    workers.submit(() -> {
                        try { processor.processNext(); }
                        catch (RuntimeException failure) {
                            // Never emit upstream response bodies, credentials or input snapshots.
                            LoggerFactory.getLogger(AnalysisWorker.class).warn("Analysis work interrupted; persisted lease will allow recovery.");
                        } finally { slots.release(); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopping) { slots.release(); return; }
            }
        }, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
        try { workers.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
