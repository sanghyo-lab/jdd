package com.jdd.commerce.logging;

import com.jdd.commerce.common.BusinessEvents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonBusinessEvents implements BusinessEvents {
    private static final Logger LOG = LoggerFactory.getLogger(JsonBusinessEvents.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock;
    private final Path root;
    private final String buildId;

    public JsonBusinessEvents(JdbcTemplate jdbc, Clock clock,
            @Value("${jdd.build-id}") String buildId,
            @Value("${jdd.commerce-log-root:runtime/evidence/logs/commerce}") String root) {
        if (!buildId.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid buildId");
        this.jdbc = jdbc; this.clock = clock; this.buildId = buildId; this.root = Path.of(root);
    }
    private String payload(String event, Trace trace, Map<String, Object> details) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "1.0"); data.put("eventId", UUID.randomUUID().toString());
        data.put("timestamp", clock.instant().toString()); data.put("level", "INFO");
        data.put("service", "commerce-app"); data.put("buildId", buildId); data.put("event", event);
        data.put("requestId", trace.requestId()); data.put("checkoutKey", trace.checkoutKey());
        data.put("orderId", trace.orderId()); data.put("paymentId", trace.paymentId());
        data.put("productId", trace.productId()); data.put("customerCouponId", trace.customerCouponId());
        data.put("details", details);
        return mapper.writeValueAsString(data);
    }
    @Override public void observed(String event, Trace trace, Map<String, Object> details) {
        append(buildId, payload(event, trace, details));
    }
    @Override public void afterCommit(String event, Trace trace, Map<String, Object> details) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Business write events require an active transaction");
        }
        // This row commits or rolls back with the business write; the publisher only sees committed rows.
        jdbc.update("INSERT INTO commerce.event_outbox (build_id, payload, published) VALUES (?, ?, false)",
                buildId, payload(event, trace, details));
    }
    @Scheduled(fixedDelayString = "${jdd.log-publish-delay-ms:100}")
    public synchronized void publishPending() {
        try {
            var rows = jdbc.queryForList("SELECT id, build_id, payload FROM commerce.event_outbox "
                    + "WHERE published = false ORDER BY id LIMIT 100");
            for (var row : rows) {
                append((String) row.get("build_id"), (String) row.get("payload"));
                jdbc.update("UPDATE commerce.event_outbox SET published = true WHERE id = ?", row.get("id"));
            }
        } catch (RuntimeException failure) {
            // Keep the durable row for retry. A committed order must not become an HTTP failure on log I/O.
            LOG.error("Business log export is pending; durable events will be retried ({})", failure.getClass().getSimpleName());
        }
    }
    private synchronized void append(String build, String payload) {
        if (!build.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid event buildId");
        try {
            Path directory = root.resolve(build);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("business.jsonl"), payload + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Business log output unavailable", failure);
        }
    }
}
