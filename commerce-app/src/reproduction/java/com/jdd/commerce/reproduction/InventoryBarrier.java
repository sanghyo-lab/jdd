package com.jdd.commerce.reproduction;

import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import com.jdd.commerce.inventory.port.InventoryReadObserver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Test-only control. No fixture, answer, or synchronization source is exported to Agent evidence. */
@RestController
@RequestMapping("/internal/reproduction/inventory-barrier")
@ConditionalOnProperty(name = "jdd.reproduction-enabled", havingValue = "true")
public class InventoryBarrier implements InventoryReadObserver {
    public record Arm(String productId, List<String> checkoutKeys, Integer timeoutMs) {}
    public record Arrival(String checkoutKey, int observedQuantity, int backendPid, long transactionId) {}
    public record View(String id, String productId, String status, List<Arrival> arrivals) {}
    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final String productId;
        final Set<String> keys;
        final long deadline;
        final Map<String, Arrival> arrivals = new LinkedHashMap<>();
        String status = "ARMED";
        Session(Arm arm) {
            productId = arm.productId(); keys = Set.copyOf(arm.checkoutKeys());
            deadline = System.nanoTime() + arm.timeoutMs() * 1_000_000L;
        }
        View view() { return new View(id, productId, status, List.copyOf(arrivals.values())); }
        void expire() { if (status.equals("ARMED") && System.nanoTime() >= deadline) status = "TIMED_OUT"; }
    }
    private final JdbcTemplate jdbc;
    private Session session;
    public InventoryBarrier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping public synchronized View arm(@RequestBody Arm input) {
        Inputs.identifier(input.productId(), "productId");
        if (input.checkoutKeys() == null || input.checkoutKeys().size() != 2
                || input.timeoutMs() == null || input.timeoutMs() < 100 || input.timeoutMs() > 10000) {
            throw CommerceException.invalid("Two distinct checkoutKeys and timeoutMs 100..10000 are required");
        }
        input.checkoutKeys().forEach(key -> Inputs.identifier(key, "checkoutKey"));
        if (input.checkoutKeys().get(0).equals(input.checkoutKeys().get(1))) {
            throw CommerceException.invalid("checkoutKeys must be distinct");
        }
        if (session != null) {
            session.expire();
            if (session.status.equals("ARMED")) throw new CommerceException(409, "REQUEST_KEY_CONFLICT", "A barrier is already armed", false);
        }
        session = new Session(input);
        return session.view();
    }
    @GetMapping("/{id}") public synchronized View view(@PathVariable String id) {
        Session current = require(id); current.expire(); return current.view();
    }
    @DeleteMapping("/{id}") public synchronized View release(@PathVariable String id) {
        Session current = require(id);
        current.status = "RELEASED";
        notifyAll(); return current.view();
    }
    private Session require(String id) {
        if (session == null || !session.id.equals(id)) throw CommerceException.notFound("Barrier");
        return session;
    }
    @Override public synchronized void observed(String productId, String checkoutKey, int quantity) {
        Session current = session;
        if (current == null || !current.productId.equals(productId) || !current.keys.contains(checkoutKey)) return;
        if (current.status.equals("RELEASED")) return;
        current.expire();
        if (!current.status.equals("ARMED")) throw unavailable();
        if (current.arrivals.containsKey(checkoutKey)) throw unavailable();
        Arrival arrival = jdbc.queryForObject("SELECT pg_backend_pid() AS pid, txid_current() AS txid",
                (row, n) -> new Arrival(checkoutKey, quantity, row.getInt("pid"), row.getLong("txid")));
        current.arrivals.put(checkoutKey, arrival);
        if (current.arrivals.size() == 2) { current.status = "COMPLETED"; notifyAll(); }
        while (current.status.equals("ARMED")) {
            current.expire();
            if (!current.status.equals("ARMED")) break;
            try { wait(Math.max(1, (current.deadline - System.nanoTime()) / 1_000_000)); }
            catch (InterruptedException interrupted) {
                current.status = "INTERRUPTED"; notifyAll(); Thread.currentThread().interrupt(); throw unavailable();
            }
        }
        if (!current.status.equals("COMPLETED")) { notifyAll(); throw unavailable(); }
    }
    private CommerceException unavailable() {
        return new CommerceException(503, "DEPENDENCY_UNAVAILABLE", "Reproduction barrier did not complete", true);
    }
}
