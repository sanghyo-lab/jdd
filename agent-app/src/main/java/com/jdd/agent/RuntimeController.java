package com.jdd.agent;

import com.jdd.agent.domain.InvestigationModel;
import java.util.Map;
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
    private final InvestigationModel model;
    private final boolean workerEnabled;

    public RuntimeController(JdbcTemplate jdbc,
            @Value("${jdd.build-id}") String buildId,
            @Value("${jdd.commit-sha}") String commitSha, InvestigationModel model,
            @Value("${jdd.agent.worker.enabled:true}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.buildId = buildId;
        this.commitSha = commitSha;
        this.model = model;
        this.workerEnabled = workerEnabled;
    }

    @GetMapping("/internal/runtime")
    public Map<String, Object> runtime() {
        String storedName = jdbc.queryForObject(
                "SELECT service_name FROM agent.bootstrap_probe WHERE id = 'bootstrap'", String.class);
        return Map.of("service", storedName, "buildId", buildId, "commitSha", commitSha,
                "stage", "IMPLEMENTING", "schema", "agent", "businessReady", false,
                "investigationModel", model.mode().name(), "workerEnabled", workerEnabled);
    }
}
