package com.jdd.agent;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local integration diagnostics, not a business API or a completed MVP. */
@RestController
public class RuntimeController {
    private final JdbcTemplate jdbc;
    private final String buildId;
    private final String commitSha;
    private final LlmRuntimeObservation llm;
    private final InvestigationWorker worker;

    public RuntimeController(JdbcTemplate jdbc,
            @Value("${jdd.build-id}") String buildId,
            @Value("${jdd.commit-sha}") String commitSha, LlmRuntimeObservation llm,
            ObjectProvider<InvestigationWorker> workers) {
        this.jdbc = jdbc;
        this.buildId = buildId;
        this.commitSha = commitSha;
        this.llm = llm;
        this.worker = workers.getIfAvailable();
    }

    @GetMapping("/internal/runtime")
    public Map<String, Object> runtime() {
        String storedName = jdbc.queryForObject(
                "SELECT service_name FROM agent.bootstrap_probe WHERE id = 'bootstrap'", String.class);
        boolean workerReady = worker != null && worker.isReady();
        return Map.of("service", storedName, "buildId", buildId, "commitSha", commitSha,
                "stage", "IMPLEMENTING", "schema", "agent", "businessReady", workerReady && llm.usesActualAdapter(),
                "investigationModel", llm.mode().name(), "workerEnabled", worker != null, "workerReady", workerReady,
                "llm", llm.configuration());
    }
}
