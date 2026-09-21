package com.jdd.voc.domain;

import java.time.Instant;
import java.util.Map;

public record Ticket(String ticketId, long version, String title, String message,
        Map<String, String> context, String assigneeId, Status status,
        Instant createdAt, Instant updatedAt) {
    public Ticket { context = Map.copyOf(context); }
    public enum Status { OPEN, IN_PROGRESS, RESOLVED }
}
