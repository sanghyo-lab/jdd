package com.jdd.voc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DependencyController {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String agentUrl;
    private final String commerceUrl;

    public DependencyController(
            @Value("${AGENT_BASE_URL:http://localhost:8081}") String agentUrl,
            @Value("${COMMERCE_BASE_URL:http://localhost:8080}") String commerceUrl) {
        this.agentUrl = agentUrl;
        this.commerceUrl = commerceUrl;
    }

    @GetMapping("/internal/dependencies")
    public Map<String, Object> dependencies() throws Exception {
        return Map.of("agentHttpStatus", getStatus(agentUrl), "commerceHttpStatus", getStatus(commerceUrl));
    }

    private int getStatus(String baseUrl) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/runtime"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
