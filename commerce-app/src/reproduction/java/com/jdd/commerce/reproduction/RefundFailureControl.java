package com.jdd.commerce.reproduction;

import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import com.jdd.commerce.payment.port.RefundFault;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/reproduction/refund-failure")
@ConditionalOnProperty(name = "jdd.reproduction-enabled", havingValue = "true")
public class RefundFailureControl implements RefundFault {
    private record Key(String orderId, String requestKey) {}
    private final Set<Key> pending = new HashSet<>();
    @PostMapping public synchronized Map<String, String> arm(@RequestBody JsonNode body) {
        Key key = key(body);
        if (pending.size() >= 128 && !pending.contains(key)) throw CommerceException.invalid("Too many pending failure controls");
        pending.add(key);
        return Map.of("status", "ARMED", "orderId", key.orderId(), "requestKey", key.requestKey());
    }
    @DeleteMapping public synchronized Map<String, String> clear(@RequestBody JsonNode body) {
        pending.remove(key(body));
        return Map.of("status", "CLEARED");
    }
    @Override public synchronized boolean failOnce(String orderId, String requestKey) {
        return pending.remove(new Key(orderId, requestKey));
    }
    private Key key(JsonNode body) {
        if (!body.isObject() || !body.path("orderId").isString() || !body.path("requestKey").isString()) {
            throw CommerceException.invalid("orderId and requestKey must be strings");
        }
        return new Key(Inputs.identifier(body.path("orderId").stringValue(), "orderId"),
                Inputs.identifier(body.path("requestKey").stringValue(), "requestKey"));
    }
}
