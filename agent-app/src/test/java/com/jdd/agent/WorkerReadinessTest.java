package com.jdd.agent;

import com.jdd.agent.domain.InvestigationExecutionRepository;
import com.jdd.agent.domain.InvestigationRunner;
import com.jdd.agent.domain.ModelCallLedger;
import com.jdd.agent.infra.JdbcWorkerOwnership;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerReadinessTest {
    @Test void readinessRequiresOwnershipAndRecoveryAndEndsOnStop() throws Exception {
        var ownership = mock(JdbcWorkerOwnership.class);
        when(ownership.acquire()).thenReturn(true);
        when(ownership.valid()).thenReturn(true);
        var executions = mock(InvestigationExecutionRepository.class);
        when(executions.claimNext(any(), any())).thenReturn(Optional.empty());
        var ledger = mock(ModelCallLedger.class);
        var runner = mock(InvestigationRunner.class);
        var worker = new InvestigationWorker(executions, runner, ledger, ownership, 1, Duration.ofSeconds(3));
        try {
            assertThat(worker.isReady()).isFalse();
            worker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!worker.isReady() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(worker.isReady()).isTrue();
            verify(ledger).recoverUnsettled(any());
            verify(executions).recoverInterrupted(any());
            verifyNoInteractions(runner);
        } finally { worker.stop(); }
        assertThat(worker.isReady()).isFalse();
    }

    @Test void failedRecoveryDoesNotMarkWorkerReady() throws Exception {
        var ownership = mock(JdbcWorkerOwnership.class);
        var closed = new CountDownLatch(1);
        doAnswer(call -> { closed.countDown(); return null; }).when(ownership).close();
        when(ownership.acquire()).thenReturn(true);
        var ledger = mock(ModelCallLedger.class);
        when(ledger.recoverUnsettled(any())).thenThrow(new IllegalStateException("synthetic unavailable ledger"));
        var executions = mock(InvestigationExecutionRepository.class);
        var runner = mock(InvestigationRunner.class);
        var worker = new InvestigationWorker(executions, runner, ledger, ownership, 1, Duration.ofSeconds(3));
        try {
            worker.start();
            // Do not hold the synchronized mock method's monitor inside Mockito timeout verification.
            assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
            verify(ownership, atLeastOnce()).close();
            assertThat(worker.isReady()).isFalse();
            verifyNoInteractions(executions, runner);
        } finally { worker.stop(); }
    }
}
