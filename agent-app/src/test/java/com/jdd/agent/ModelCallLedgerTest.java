package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.ModelCallLedger.*;
import com.jdd.agent.domain.PaidModelGate.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:budget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class ModelCallLedgerTest {
    @DynamicPropertySource static void dedicatedPostgres(DynamicPropertyRegistry properties) {
        String url = System.getenv("JDD_BUDGET_TEST_DB_URL");
        if (url == null) return;
        if (!url.matches("jdbc:postgresql://[^/]+/jdd_agent_budget_test(?:\\?.*)?"))
            throw new IllegalStateException("External ledger tests require the dedicated test database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("JDD_BUDGET_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("JDD_BUDGET_TEST_DB_PASSWORD"));
    }

    @Autowired ModelCallLedger ledger;
    @Autowired InvestigationRepository investigations;
    @Autowired JdbcTemplate jdbc;
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Budget budget = new Budget("synthetic-demo", new BigDecimal("0.01"), 20, 10);
    private final ModelPricing pricing = new ModelPricing("synthetic-price-1", "mock-only",
            new BigDecimal("2"), new BigDecimal("0.2"), new BigDecimal("2.5"), new BigDecimal("12"), true);

    @BeforeEach void cleanDedicatedDatabase() {
        String url = jdbc.execute((java.sql.Connection connection) -> connection.getMetaData().getURL());
        if (!url.startsWith("jdbc:h2:mem:budget") && !url.matches("jdbc:postgresql://[^/]+/jdd_agent_budget_test(?:\\?.*)?"))
            throw new IllegalStateException("Refusing to clear a non-test database");
        jdbc.update("DELETE FROM agent.model_calls");
        jdbc.update("DELETE FROM agent.demo_budget");
        jdbc.update("DELETE FROM agent.investigation_evidence");
        jdbc.update("DELETE FROM agent.investigations");
    }

    @Test void concurrentReservationsCannotOverspend() throws Exception {
        ledger.configure(budget);
        String id = investigation();
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            Callable<Boolean> reserve = () -> { barrier.await(); return ledger.reserve(request(id), now).isPresent(); };
            int accepted = 0;
            for (var result : pool.invokeAll(java.util.Collections.nCopies(8, reserve))) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(2); // Each maximum is $0.0049.
        }
        assertThat(ledger.totals().reservedUsd()).isEqualByComparingTo("0.0098");
        assertThat(ledger.totals().committedUsd()).isLessThanOrEqualTo(budget.limitUsd());
    }

    @Test void restartKeepsUnknownReservationAndReinvestigationCannotResetIt() {
        ledger.configure(budget);
        var request = request(investigation());
        ledger.reserve(request, now).orElseThrow();
        assertThat(ledger.dispatch(request.callId(), now)).isTrue();
        assertThat(ledger.recoverUnsettled(now.plusSeconds(1))).isEqualTo(1);
        ledger.configure(budget);
        assertThat(ledger.recoverUnsettled(now.plusSeconds(2))).isZero();
        assertThat(ledger.find(request.callId()).orElseThrow().receipt().usage()).isNull();
        assertThat(ledger.totals().unknownUsd()).isEqualByComparingTo("0.0049");
        assertThat(ledger.reserve(request(investigation()), now)).isEmpty();
        assertThatThrownBy(() -> ledger.configure(new Budget("different-scope", new BigDecimal("30"), 20, 10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Budget("synthetic-demo", new BigDecimal("30.01"), 20, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateReceiptsDoNotDoubleChargeAndLateUsageCanResolveUnknown() {
        ledger.configure(budget);
        var request = request(investigation());
        ledger.reserve(request, now).orElseThrow();
        ledger.dispatch(request.callId(), now);
        ledger.recoverUnsettled(now.plusSeconds(1));
        var receipt = receipt(new ModelUsage(1000L, 200L, 600L, 100L, 150L));
        assertThat(ledger.settle(request.callId(), receipt).state()).isEqualTo(State.CONFIRMED);
        ledger.settle(request.callId(), receipt);
        assertThat(ledger.totals().confirmedUsd()).isEqualByComparingTo("0.00337");
        assertThat(ledger.totals().unknownUsd()).isZero();
        assertThat(ledger.totals().reservedUsd()).isZero();
        assertThatThrownBy(() -> ledger.settle(request.callId(), receipt(new ModelUsage(10L, 2L, 0L, 0L, 0L))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingCacheWritesOrDifferentActualModelNeverBecomeFree() {
        ledger.configure(budget);
        var request = request(investigation());
        ledger.reserve(request, now).orElseThrow();
        ledger.dispatch(request.callId(), now);
        var missing = ledger.settle(request.callId(), receipt(new ModelUsage(1000L, 200L, 0L, null, null)));
        assertThat(missing.state()).isEqualTo(State.UNKNOWN);
        assertThat(missing.confirmedUsd()).isNull();
        assertThat(missing.receipt().usage().cacheWriteTokens()).isNull();
        assertThat(ledger.settle(request.callId(), new Receipt("request-1", "unpriced-model",
                new ModelUsage(1000L, 200L, 0L, 0L, 0L), "SUCCESS", now)).state()).isEqualTo(State.UNKNOWN);
        assertThat(ledger.totals().unknownUsd()).isEqualByComparingTo("0.0049");
    }

    @Test void actualOverrunIsRecordedAndStopsFurtherCalls() {
        ledger.configure(budget);
        var request = request(investigation());
        ledger.reserve(request, now).orElseThrow();
        ledger.dispatch(request.callId(), now);
        ledger.settle(request.callId(), receipt(new ModelUsage(1000L, 1000L, 0L, 0L, 900L)));
        assertThat(ledger.totals().confirmedUsd()).isEqualByComparingTo("0.014");
        assertThat(ledger.reserve(request(investigation()), now)).isEmpty();
    }

    @Test void attemptsAndDispatchCannotBeReplayedOrCancelledAfterSending() {
        ledger.configure(budget);
        var request = request(investigation());
        ledger.reserve(request, now).orElseThrow();
        assertThatThrownBy(() -> ledger.reserve(request, now)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.dispatch(request.callId(), now)).isTrue();
        assertThat(ledger.dispatch(request.callId(), now)).isFalse();
        assertThatThrownBy(() -> ledger.cancelUnsent(request.callId(), now)).isInstanceOf(IllegalStateException.class);
        var unsent = request(investigation());
        ledger.reserve(unsent, now).orElseThrow();
        assertThat(ledger.cancelUnsent(unsent.callId(), now).state()).isEqualTo(State.CANCELLED);
        assertThat(ledger.totals().reservedUsd()).isEqualByComparingTo("0.0049");
    }

    @Test void enforcesPersistentConcurrencyAndPerInvestigationCallLimits() {
        ledger.configure(new Budget("synthetic-demo", new BigDecimal("1"), 1, 1));
        String id = investigation();
        var request = request(id);
        ledger.reserve(request, now).orElseThrow();
        assertThat(ledger.reserve(request(investigation()), now)).isEmpty();
        ledger.dispatch(request.callId(), now);
        ledger.settle(request.callId(), receipt(new ModelUsage(1L, 1L, 0L, 0L, 0L)));
        assertThat(ledger.reserve(request(id), now)).isEmpty();
        assertThat(ledger.reserve(request(investigation()), now)).isPresent();
    }

    @Test void disabledExpiredWrongScopeAndUnapprovedModelNeverInvokeTransport() {
        var calls = new AtomicInteger();
        var request = request(investigation());
        for (var auth : List.of(Authorization.disabled(),
                new Authorization(true, false, budget.scope(), now.plusSeconds(1), Set.of("mock-only")),
                new Authorization(true, true, budget.scope(), now, Set.of("mock-only")),
                new Authorization(true, true, "wrong", now.plusSeconds(1), Set.of("mock-only")),
                new Authorization(true, true, budget.scope(), now.plusSeconds(1), Set.of("different")))) {
            var gate = new PaidModelGate(auth, budget, ledger, clock);
            assertThatThrownBy(() -> gate.call(request, () -> { calls.incrementAndGet(); return null; }))
                    .isInstanceOf(Rejected.class);
        }
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
    }

    @Test void permittedMockTransportChargesOnceAndFailureStaysUnknownWithoutRetry() {
        var gate = new PaidModelGate(new Authorization(true, true, budget.scope(), now.plusSeconds(60), Set.of("mock-only")), budget, ledger, clock);
        var calls = new AtomicInteger();
        String id = investigation();
        assertThat(gate.call(request(id), () -> {
            calls.incrementAndGet();
            return new Result<>("mock-response", receipt(new ModelUsage(1000L, 200L, 600L, 100L, 150L)));
        })).isEqualTo("mock-response");
        assertThatThrownBy(() -> gate.call(request(id), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("sensitive provider text must not escape");
        })).isInstanceOf(Rejected.class).hasMessageNotContaining("sensitive");
        assertThat(calls).hasValue(2);
        assertThat(ledger.totals().confirmedUsd()).isEqualByComparingTo("0.00337");
        assertThat(ledger.totals().unknownUsd()).isEqualByComparingTo("0.0049");
        assertThatThrownBy(() -> gate.call(request(id), () -> { calls.incrementAndGet(); return null; })).isInstanceOf(Rejected.class);
        assertThat(calls).hasValue(2);
    }

    @Test void approvalExpiringDuringReservationCancelsWithoutDispatch() {
        var reads = new AtomicInteger();
        Clock advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.plusSeconds(reads.getAndIncrement() * 2L); }
        };
        var gate = new PaidModelGate(new Authorization(true, true, budget.scope(), now.plusSeconds(1), Set.of("mock-only")),
                budget, ledger, advancing);
        var calls = new AtomicInteger();
        var request = request(investigation());
        assertThatThrownBy(() -> gate.call(request, () -> { calls.incrementAndGet(); return null; })).isInstanceOf(Rejected.class);
        assertThat(calls).hasValue(0);
        assertThat(ledger.find(request.callId()).orElseThrow().state()).isEqualTo(State.CANCELLED);
        assertThat(ledger.totals().committedUsd()).isZero();
    }

    private String investigation() {
        return new InvestigationService(investigations, clock).submit(new InvestigationInput("1.0", UUID.randomUUID().toString(),
                1, "test", "합성 비용 검증", null, null)).investigationId();
    }
    private Request request(String id) {
        return new Request(UUID.randomUUID().toString(), id, 1, "https://mock.invalid/v1", "test", "test-prompt-1",
                "synthetic-hash", "test-tools-1", "{}", pricing, 1000, 200);
    }
    private Receipt receipt(ModelUsage usage) { return new Receipt("mock-provider-request", "mock-only", usage, "SUCCESS", now); }
}
