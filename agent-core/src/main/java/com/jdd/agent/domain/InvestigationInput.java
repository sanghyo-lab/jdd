package com.jdd.agent.domain;

import java.time.Instant;

public record InvestigationInput(String schemaVersion, String ticketId, Integer ticketVersion,
                                String requestKey, String message, Context context,
                                String previousInvestigationId) {
    public InvestigationInput {
        if (!"1.0".equals(schemaVersion)) throw InvestigationException.invalid("schemaVersion must be 1.0");
        requireText(ticketId, "ticketId");
        requireText(requestKey, "requestKey");
        if (ticketVersion == null || ticketVersion < 1) {
            throw InvestigationException.invalid("ticketVersion must be a positive integer");
        }
        if (message == null || message.isBlank() || message.codePointCount(0, message.length()) > 10_000) {
            throw InvestigationException.invalid("message must contain 1 to 10000 characters");
        }
        context = context == null ? new Context(null, null, null, null, null, null) : context;
        if (previousInvestigationId != null) requireText(previousInvestigationId, "previousInvestigationId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw InvestigationException.invalid(field + " must not be blank");
    }

    public record Context(String customerId, String orderId, String productId, String requestId,
                          String checkoutKey, Instant occurredAt) {
        public Context {
            if (customerId != null) requireText(customerId, "context.customerId");
            if (orderId != null) requireText(orderId, "context.orderId");
            if (productId != null) requireText(productId, "context.productId");
            if (requestId != null) requireText(requestId, "context.requestId");
            if (checkoutKey != null) requireText(checkoutKey, "context.checkoutKey");
        }
    }
}
