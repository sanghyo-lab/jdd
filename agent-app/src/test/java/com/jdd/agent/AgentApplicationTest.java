package com.jdd.agent;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "jdd.agent.worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.create-schemas=true",
    "jdd.build-id=test-build",
    "jdd.commit-sha=test-commit"
})
class AgentApplicationTest {
    @Autowired RuntimeController runtime;

    @Test void migrationsAndRuntimeIdentifyThisBuild() {
        Map<String, Object> result = runtime.runtime();
        assertThat(result).containsEntry("service", "agent-app")
                .containsEntry("schema", "agent")
                .containsEntry("buildId", "test-build")
                .containsEntry("businessReady", false)
                .containsEntry("llm", Map.of("runtime", "test", "provider", "mock", "configuredModel", "mock"));
    }
}
