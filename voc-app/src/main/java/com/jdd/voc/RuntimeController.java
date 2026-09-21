package com.jdd.voc;

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
    private final boolean workerEnabled;

    public RuntimeController(JdbcTemplate jdbc,
            @Value("${jdd.build-id}") String buildId,
            @Value("${jdd.commit-sha}") String commitSha,
            ObjectProvider<AnalysisWorker> workers) {
        this.jdbc = jdbc;
        this.buildId = buildId;
        this.commitSha = commitSha;
        this.workerEnabled = workers.getIfAvailable() != null;
    }

    @GetMapping("/internal/runtime")
    public Map<String, Object> runtime() {
        String storedName = jdbc.queryForObject(
                "SELECT service_name FROM voc.bootstrap_probe WHERE id = 'bootstrap'", String.class);
        return Map.of("service", storedName, "buildId", buildId, "commitSha", commitSha,
                "stage", "IMPLEMENTING", "schema", "voc", "businessReady", workerEnabled,
                "workerEnabled", workerEnabled);
    }
}
