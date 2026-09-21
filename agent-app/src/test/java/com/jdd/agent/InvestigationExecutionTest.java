package com.jdd.agent;

import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationExecutionRepository;
import com.jdd.agent.domain.InvestigationExecutionRepository.*;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationRepository;
import com.jdd.agent.domain.InvestigationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "jdd.agent.worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:execution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class InvestigationExecutionTest {
    @DynamicPropertySource static void dedicatedPostgres(DynamicPropertyRegistry properties) {
        String url = System.getenv("JDD_EXECUTION_TEST_DB_URL");
        if (url == null) return;
        if (!url.matches("jdbc:postgresql://[^/]+/jdd_agent_execution_test(?:\\?.*)?")) {
            throw new IllegalStateException("External execution tests require the dedicated test database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("JDD_EXECUTION_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("JDD_EXECUTION_TEST_DB_PASSWORD"));
    }

    @Autowired InvestigationExecutionRepository executions;
    @Autowired InvestigationRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");

    @BeforeEach void cleanDedicatedTestDatabase() {
        // The optional external URL MUST refer to a dedicated, disposable test database.
        String url = jdbc.execute((java.sql.Connection connection) -> connection.getMetaData().getURL());
        if (!url.startsWith("jdbc:h2:mem:execution") && !url.matches("jdbc:postgresql://[^/]+/jdd_agent_execution_test(?:\\?.*)?")) {
            throw new IllegalStateException("Refusing to clear a non-test database");
        }
        jdbc.update("DELETE FROM agent.investigation_evidence");
        jdbc.update("DELETE FROM agent.investigations");
    }

    @Test void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        String id = submit();
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            Callable<Boolean> task = () -> {
                barrier.await();
                return executions.claimNext(now, Duration.ofMinutes(3)).isPresent();
            };
            var results = pool.invokeAll(java.util.Collections.nCopies(8, task));
            int winners = 0;
            for (var result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(repository.find(id).orElseThrow().investigation().status()).isEqualTo(Status.RUNNING);
    }

    @Test void commitsRawEvidenceAndProgressBeforeFinishingReport() {
        var claim = start();
        String tool = executions.beginTool(claim, "getInventoryContext", now.plusSeconds(1)).orElseThrow();
        var details = executions.completeTool(claim, tool, "재고 1행 조회", List.of(observation()), now.plusSeconds(2)).orElseThrow();
        String evidenceId = details.getFirst().evidenceId();
        assertThat(repository.findEvidence(claim.investigationId(), evidenceId)).contains(details.getFirst());
        var observed = repository.find(claim.investigationId()).orElseThrow().investigation();
        assertThat(observed.report()).isNull();
        assertThat(observed.progress().getFirst().status()).isEqualTo(ToolStatus.SUCCEEDED);
        assertThat(observed.progress().getFirst().evidenceIds()).containsExactly(evidenceId);
        assertThat(repository.findEvidence("different-investigation", evidenceId)).isEmpty();

        var report = report(List.of(new Fact("f1", "재고 조회 결과", List.of(evidenceId))), List.of());
        assertThat(executions.complete(claim, report, now.plusSeconds(3))).isTrue();
        var done = repository.find(claim.investigationId()).orElseThrow().investigation();
        assertThat(done.status()).isEqualTo(Status.COMPLETED);
        assertThat(done.report()).isEqualTo(report);
        assertThat(done.error()).isNull();
        assertThat(executions.fail(claim, new ApiError("INTERRUPTED", "늦은 실패", false), now.plusSeconds(4))).isFalse();
        assertThat(repository.find(claim.investigationId()).orElseThrow().investigation()).isEqualTo(done);
    }

    @Test void failedEvidenceBatchRollsBackBothRawRowsAndSummary() {
        var claim = start();
        String tool = executions.beginTool(claim, "getInventoryContext", now).orElseThrow();
        assertThatThrownBy(() -> executions.completeTool(claim, tool, "조회", Arrays.asList(observation(), null), now))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigation_evidence", Integer.class)).isZero();
        var view = repository.find(claim.investigationId()).orElseThrow().investigation();
        assertThat(view.evidence()).isEmpty();
        assertThat(view.progress().getFirst().status()).isEqualTo(ToolStatus.RUNNING);
    }

    @Test void recoveryPreservesEvidenceFailsActiveToolsAndFencesLateResponses() {
        var claim = start();
        String completed = executions.beginTool(claim, "getInventoryContext", now).orElseThrow();
        var details = executions.completeTool(claim, completed, "조회", List.of(observation()), now).orElseThrow();
        String interrupted = executions.beginTool(claim, "searchLogs", now.plusSeconds(1)).orElseThrow();
        String queuedId = submit();
        assertThat(executions.recoverInterrupted(now.plusSeconds(2))).isEqualTo(1);
        assertThat(executions.recoverInterrupted(now.plusSeconds(3))).isZero();
        var failed = repository.find(claim.investigationId()).orElseThrow().investigation();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.error().code()).isEqualTo("INTERRUPTED");
        assertThat(failed.report()).isNull();
        assertThat(failed.progress()).extracting(ToolExecution::status).containsExactly(ToolStatus.SUCCEEDED, ToolStatus.FAILED);
        assertThat(repository.findEvidence(claim.investigationId(), details.getFirst().evidenceId())).isPresent();
        assertThat(executions.completeTool(claim, interrupted, "늦은 결과", List.of(observation()), now.plusSeconds(4))).isEmpty();
        assertThat(executions.complete(claim, report(List.of(), List.of()), now.plusSeconds(4))).isFalse();
        assertThat(repository.find(claim.investigationId()).orElseThrow().investigation()).isEqualTo(failed);
        assertThat(executions.claimNext(now.plusSeconds(4), Duration.ofMinutes(3)).orElseThrow().investigationId()).isEqualTo(queuedId);
    }

    @Test void deadlinesRejectLateSuccessEvenBeforeExpirationSweep() {
        var claim = start();
        assertThat(executions.complete(claim, report(List.of(), List.of()), claim.deadline())).isFalse();
        assertThat(repository.find(claim.investigationId()).orElseThrow().investigation().error().code())
                .isEqualTo("INVESTIGATION_TIMEOUT");
        var another = start();
        assertThat(executions.expire(another.deadline().minusSeconds(1))).isZero();
        assertThat(executions.expire(another.deadline())).isEqualTo(1);
    }

    @Test void validatesStoredReferencesAndDerivesNeedsInputStatus() {
        var claim = start();
        assertThatThrownBy(() -> executions.complete(claim,
                report(List.of(new Fact("f1", "가짜 근거", List.of("not-stored"))), List.of()), now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.find(claim.investigationId()).orElseThrow().investigation().status()).isEqualTo(Status.RUNNING);
        assertThat(executions.complete(claim, report(List.of(),
                List.of(new MissingInformation("context.orderId", "조회 대상을 구분해 주세요."))), now)).isTrue();
        assertThat(repository.find(claim.investigationId()).orElseThrow().investigation().status()).isEqualTo(Status.NEEDS_INPUT);
    }

    @Test void refusesFinishBeforeToolsAndDuplicateToolCompletion() {
        var claim = start();
        String tool = executions.beginTool(claim, "findOrders", now).orElseThrow();
        assertThatThrownBy(() -> executions.complete(claim, report(List.of(), List.of()), now)).isInstanceOf(IllegalStateException.class);
        executions.completeTool(claim, tool, "0건", List.of(), now).orElseThrow();
        assertThatThrownBy(() -> executions.completeTool(claim, tool, "중복", List.of(observation()), now))
                .isInstanceOf(IllegalStateException.class);
    }

    private String submit() {
        return new InvestigationService(repository, Clock.fixed(now, ZoneOffset.UTC)).submit(new InvestigationInput(
                "1.0", UUID.randomUUID().toString(), 1, "test", "합성 조사 입력", null, null)).investigationId();
    }
    private Claim start() {
        submit();
        return executions.claimNext(now, Duration.ofMinutes(3)).orElseThrow();
    }
    private Observation observation() {
        return new Observation(EvidenceType.DATA, "합성 관측", now,
                Map.of("schema", "commerce", "table", "product_stock", "recordIds", List.of("synthetic-product"),
                        "queryDescription", "상품별 재고 조회"),
                Map.of("columns", List.of("product_id", "quantity"), "rows", List.of(List.of("synthetic-product", 1))), false);
    }
    private AnalysisReport report(List<Fact> facts, List<MissingInformation> missing) {
        return new AnalysisReport("1.0", "관측 결과와 확인 범위", facts, List.of(), List.of(), List.of(), missing);
    }
}
