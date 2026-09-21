package com.jdd.agent.infra;

import java.time.Duration;
import java.time.Instant;

/** Bounded tool inputs, parsed with the same strict JSON mapper as the API. */
public final class EvidenceToolArguments {
    private EvidenceToolArguments() {}
    public record FindOrders(String customerId, String orderId, String productId, String requestId, String checkoutKey,
                             Instant from, Instant to, Integer limit) {
        public FindOrders {
            optional(customerId); optional(orderId); optional(productId); optional(requestId); optional(checkoutKey);
            range(from, to);
            if (customerId == null && orderId == null && productId == null && requestId == null && checkoutKey == null
                    && (from == null || to == null)) throw invalid();
            limit = bounded(limit, 20, 1, 100);
        }
    }
    public record OrderContext(String orderId, Integer limit) {
        public OrderContext { required(orderId); limit = bounded(limit, 50, 1, 100); }
    }
    public record CouponContext(String customerId, String customerCouponId, Integer limit) {
        public CouponContext {
            optional(customerId); optional(customerCouponId);
            if (customerId == null && customerCouponId == null) throw invalid();
            limit = bounded(limit, 50, 1, 100);
        }
    }
    public record InventoryContext(String productId, Integer limit) {
        public InventoryContext { required(productId); limit = bounded(limit, 50, 1, 100); }
    }
    public record SearchLogs(String buildId, String requestId, String orderId, String productId, String checkoutKey,
                             Instant from, Instant to, Integer limit) {
        public SearchLogs {
            if (buildId != null) build(buildId);
            optional(requestId); optional(orderId); optional(productId); optional(checkoutKey); range(from, to);
            if (requestId == null && orderId == null && productId == null && checkoutKey == null
                    && (from == null || to == null)) throw invalid();
            limit = bounded(limit, 20, 1, 100);
        }
    }
    public record SearchCode(String buildId, String query, Integer limit) {
        public SearchCode { build(buildId); required(query); limit = bounded(limit, 10, 1, 30); }
    }
    public record ReadCode(String buildId, String path, Integer startLine, Integer endLine) {
        public ReadCode {
            build(buildId);
            if (path == null || path.isBlank() || path.length() > 500) throw invalid();
            if (startLine == null || endLine == null || startLine < 1 || endLine < startLine
                    || (long) endLine - startLine >= 300) throw invalid();
        }
    }
    public record ReadPolicy(String buildId, String version, String section) {
        public ReadPolicy { build(buildId); required(version); optional(section); }
    }
    private static int bounded(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        if (value < min || value > max) throw invalid();
        return value;
    }
    private static void required(String value) {
        if (value == null) throw invalid();
        optional(value);
    }
    private static void optional(String value) {
        if (value != null && (value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl))) throw invalid();
    }
    private static void build(String value) {
        required(value);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || value.contains("..")) throw invalid();
    }
    private static void range(Instant from, Instant to) {
        if ((from == null) != (to == null)) throw invalid();
        if (from != null && (from.isAfter(to) || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid evidence tool arguments"); }
}
