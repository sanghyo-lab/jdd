package com.jdd.voc;

import com.jdd.voc.domain.*;
import com.jdd.voc.infra.HttpAgentGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class AnalysisWorkerConfiguration {
    @Bean AgentGateway agentGateway(JsonMapper json,
            @Value("${AGENT_BASE_URL:http://localhost:8081}") String baseUrl,
            @Value("${jdd.voc.worker.connect-timeout-seconds:3}") long connect,
            @Value("${jdd.voc.worker.request-timeout-seconds:10}") long request) {
        return new HttpAgentGateway(baseUrl, json, Duration.ofSeconds(connect), Duration.ofSeconds(request));
    }

    @Bean AnalysisProcessor analysisProcessor(AnalysisWorkRepository repository, AgentGateway gateway, Clock clock,
            @Value("${jdd.voc.worker.lease-seconds:30}") long lease,
            @Value("${jdd.voc.worker.request-timeout-seconds:10}") long request,
            @Value("${jdd.voc.worker.poll-seconds:5}") long poll,
            @Value("${jdd.voc.worker.observation-seconds:840}") long observation,
            @Value("${jdd.voc.worker.delivery-attempts:3}") int attempts,
            @Value("${jdd.voc.worker.queue-retries:3}") int queueRetries) {
        if (lease < request + 5 || poll > 5) throw new IllegalArgumentException("VOC lease must outlast HTTP; poll interval must be at most five seconds");
        return new AnalysisProcessor(repository, gateway, clock, new AnalysisProcessor.Settings(Duration.ofSeconds(lease),
                Duration.ofSeconds(poll), Duration.ofSeconds(observation), attempts, queueRetries),
                () -> ThreadLocalRandom.current().nextLong(1001));
    }

    @Bean AnalysisAccessService analysisAccessService(AnalysisService service, AnalysisWorkRepository work, AgentGateway gateway, Clock clock) {
        return new AnalysisAccessService(service, work, gateway, clock);
    }

    @Bean @ConditionalOnProperty(name="jdd.voc.worker.enabled", havingValue="true", matchIfMissing=true)
    AnalysisWorker analysisWorker(AnalysisProcessor processor,
            @Value("${jdd.voc.worker.concurrency:4}") int concurrency,
            @Value("${jdd.voc.worker.tick-millis:1000}") long tick) {
        return new AnalysisWorker(processor, concurrency, tick);
    }
}
