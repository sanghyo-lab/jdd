package com.jdd.voc;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:voc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.create-schemas=true",
    "jdd.build-id=test-build", "jdd.voc.worker.enabled=false",
    "jdd.commit-sha=test-commit"
})
class VocApplicationTest {
    @Autowired RuntimeController runtime;

    @Test void migrationsAndRuntimeIdentifyThisBuild() {
        Map<String, Object> result = runtime.runtime();
        assertThat(result).containsEntry("service", "voc-app")
                .containsEntry("schema", "voc")
                .containsEntry("buildId", "test-build")
                .containsEntry("businessReady", false);
    }
}
