package com.jdd.voc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real migrated VOC and worker wiring; no model or upstream request is needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:voc_readiness;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true",
    "jdd.build-id=readiness-build", "jdd.commit-sha=readiness-commit", "jdd.voc.worker.enabled=true"
})
class VocRuntimeReadinessTest {
    @Autowired Environment environment;
    @Autowired AnalysisWorker worker;
    @Autowired JsonMapper json;

    @Test void migratedApplicationWithAnActualWorkerIsReadyForInvestigationDelivery() throws Exception {
        assertThat(worker).isNotNull();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + environment.getProperty("local.server.port") + "/internal/runtime"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var runtime = json.readTree(response.body());
            assertThat(runtime.path("businessReady").asBoolean()).isTrue();
            assertThat(runtime.path("workerEnabled").asBoolean()).isTrue();
            assertThat(runtime.path("buildId").asText()).isEqualTo("readiness-build");
            assertThat(runtime.has("modelVerified")).isFalse();
        }
    }
}
