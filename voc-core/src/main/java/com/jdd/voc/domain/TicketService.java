package com.jdd.voc.domain;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TicketService {
    public record Assignee(String id, String displayName) {}
    public static final List<Assignee> ASSIGNEES = List.of(
            new Assignee("sanghyo", "이상효"), new Assignee("areum", "김아름"), new Assignee("jaehong", "한재홍"));
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "customerId", "orderId", "productId", "requestId", "checkoutKey", "occurredAt");
    private final TicketRepository repository;
    private final Clock clock;

    public TicketService(TicketRepository repository, Clock clock) {
        this.repository = repository; this.clock = clock;
    }

    public Ticket create(Map<String, Object> body) {
        fields(body, Set.of("title", "message", "context", "assigneeId"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), 1,
                text(body.get("title"), "title", 200), text(body.get("message"), "message", 10_000),
                context(body.get("context")), assignee(body.get("assigneeId")), Ticket.Status.OPEN, now, now);
        repository.insert(ticket);
        return ticket;
    }

    public Ticket get(String id) { return repository.find(id).orElseThrow(VocFailure::notFound); }

    public List<Ticket> list(String status, String assigneeId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw VocFailure.invalid("limit 또는 offset 범위를 확인해 주세요.");
        return repository.list(status == null ? null : status(status), assignee(assigneeId), limit, offset);
    }

    public Ticket patch(String id, Map<String, Object> body) {
        fields(body, Set.of("expectedVersion", "title", "message", "context", "assigneeId", "status"));
        long expected = positiveInteger(body.get("expectedVersion"), "expectedVersion");
        if (body.size() <= 1) throw VocFailure.invalid("하나 이상의 변경 필드가 필요합니다.");
        Ticket current = get(id);
        if (current.version() != expected) throw VocFailure.versionConflict();
        if (body.containsKey("context") && body.get("context") == null) throw VocFailure.invalid("context는 null일 수 없습니다.");
        Ticket updated = new Ticket(id, Math.addExact(expected, 1),
                body.containsKey("title") ? text(body.get("title"), "title", 200) : current.title(),
                body.containsKey("message") ? text(body.get("message"), "message", 10_000) : current.message(),
                body.containsKey("context") ? context(body.get("context")) : current.context(),
                body.containsKey("assigneeId") ? assignee(body.get("assigneeId")) : current.assigneeId(),
                body.containsKey("status") ? status(body.get("status")) : current.status(),
                current.createdAt(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        // Compare-and-set in storage protects against a concurrent update after the read.
        if (!repository.replace(updated, expected)) throw VocFailure.versionConflict();
        return updated;
    }

    public static long positiveInteger(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger))
            throw VocFailure.invalid(field + "는 양의 정수여야 합니다.");
        try {
            long number = Long.parseLong(value.toString());
            if (number > 0) return number;
        } catch (NumberFormatException ignored) { }
        throw VocFailure.invalid(field + "는 양의 정수여야 합니다.");
    }

    private static void fields(Map<String, Object> body, Set<String> allowed) {
        if (body == null || !allowed.containsAll(body.keySet())) throw VocFailure.invalid("지원하지 않는 요청 필드입니다.");
    }
    private static String text(Object value, String field, int maxLength) {
        if (!(value instanceof String s) || s.isBlank() || s.codePointCount(0, s.length()) > maxLength)
            throw VocFailure.invalid(field + "의 형식이나 길이를 확인해 주세요.");
        return s;
    }
    private static String assignee(Object value) {
        if (value == null) return null;
        if (value instanceof String s && ASSIGNEES.stream().anyMatch(a -> a.id().equals(s))) return s;
        throw VocFailure.invalid("알 수 없는 담당자입니다.");
    }
    private static Ticket.Status status(Object value) {
        if (value instanceof String s) {
            try { return Ticket.Status.valueOf(s); } catch (IllegalArgumentException ignored) { }
        }
        throw VocFailure.invalid("지원하지 않는 티켓 상태입니다.");
    }
    private static Map<String, String> context(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> fields)) throw VocFailure.invalid("context는 객체여야 합니다.");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !CONTEXT_FIELDS.contains(key))
                throw VocFailure.invalid("지원하지 않는 context 필드입니다.");
            if (entry.getValue() == null) continue;
            if (!(entry.getValue() instanceof String s)) throw VocFailure.invalid("context 값은 문자열이어야 합니다.");
            if (s.isBlank()) throw VocFailure.invalid("context." + key + "는 빈 문자열일 수 없습니다. 정보가 없으면 생략해 주세요.");
            if (key.equals("occurredAt")) {
                try { result.put(key, Instant.parse(s).toString()); }
                catch (DateTimeParseException error) { throw VocFailure.invalid("occurredAt은 시간대를 포함한 시각이어야 합니다."); }
            } else result.put(key, s);
        }
        return result;
    }
}
