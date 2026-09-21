package com.jdd.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DependencyController {
    private final String evidenceUrl;
    private final String evidenceUser;
    private final String evidencePassword;
    private final Path sourceRoot;
    private final Path logRoot;
    private final Path policyPath;
    private final String buildId;

    public DependencyController(
            @Value("${EVIDENCE_DB_URL:jdbc:postgresql://localhost:5432/jdd}") String evidenceUrl,
            @Value("${EVIDENCE_DB_USERNAME:jdd_evidence}") String evidenceUser,
            @Value("${EVIDENCE_DB_PASSWORD:}") String evidencePassword,
            @Value("${SOURCE_ROOT:runtime/evidence/source}") String sourceRoot,
            @Value("${LOG_ROOT:runtime/evidence/logs/commerce}") String logRoot,
            @Value("${POLICY_PATH:docs/business-policy.md}") String policyPath,
            @Value("${jdd.build-id}") String buildId) {
        this.evidenceUrl = evidenceUrl;
        this.evidenceUser = evidenceUser;
        this.evidencePassword = evidencePassword;
        this.sourceRoot = Path.of(sourceRoot);
        this.logRoot = Path.of(logRoot);
        this.policyPath = Path.of(policyPath);
        this.buildId = buildId;
    }

    @GetMapping("/internal/dependencies")
    public Map<String, Object> dependencies() throws Exception {
        // This connection is intentionally distinct from the writable agent DataSource.
        try (var connection = DriverManager.getConnection(evidenceUrl, evidenceUser, evidencePassword);
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("""
                    SELECT service_name,
                           has_table_privilege(current_user, 'commerce.bootstrap_probe', 'SELECT') AS can_read,
                           has_table_privilege(current_user, 'commerce.bootstrap_probe', 'INSERT,UPDATE,DELETE') AS can_write,
                           has_schema_privilege(current_user, 'commerce', 'CREATE') AS can_create
                    FROM commerce.bootstrap_probe WHERE id = 'bootstrap'
                    """)) {
                if (!rows.next()) throw new IllegalStateException("Commerce bootstrap probe is missing");
                return Map.of(
                        "commerceService", rows.getString("service_name"),
                        "canReadCommerce", rows.getBoolean("can_read"),
                        "canWriteCommerce", rows.getBoolean("can_write"),
                        "canCreateCommerce", rows.getBoolean("can_create"),
                        "sourceManifestAvailable", Files.isRegularFile(sourceRoot.resolve(buildId).resolve("manifest.json")),
                        "logDirectoryAvailable", Files.isDirectory(logRoot.resolve(buildId)),
                        "policyAvailable", Files.isReadable(policyPath));
            }
        }
    }
}
