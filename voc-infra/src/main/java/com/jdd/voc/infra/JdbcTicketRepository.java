package com.jdd.voc.infra;

import com.jdd.voc.domain.Ticket;
import com.jdd.voc.domain.TicketRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository {
    private static final String COLUMNS = "ticket_id, version, title, message, assignee_id, status, "
            + "context_customer_id, context_order_id, context_product_id, context_request_id, context_checkout_key, "
            + "context_occurred_at, created_at, updated_at";
    private final JdbcTemplate jdbc;
    public JdbcTicketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(Ticket t) {
        Map<String, String> c = t.context();
        jdbc.update("INSERT INTO voc.tickets (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                t.ticketId(), t.version(), t.title(), t.message(), t.assigneeId(), t.status().name(),
                c.get("customerId"), c.get("orderId"), c.get("productId"), c.get("requestId"), c.get("checkoutKey"),
                c.get("occurredAt"), Timestamp.from(t.createdAt()), Timestamp.from(t.updatedAt()));
    }

    @Override public Optional<Ticket> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM voc.tickets WHERE ticket_id = ?", this::map, id).stream().findFirst();
    }

    @Override public List<Ticket> list(Ticket.Status status, String assigneeId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM voc.tickets WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { sql.append(" AND status = ?"); args.add(status.name()); }
        if (assigneeId != null) { sql.append(" AND assignee_id = ?"); args.add(assigneeId); }
        sql.append(" ORDER BY created_at DESC, ticket_id DESC LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override public boolean replace(Ticket t, long expected) {
        Map<String, String> c = t.context();
        return jdbc.update("""
                UPDATE voc.tickets SET version=?, title=?, message=?, assignee_id=?, status=?,
                    context_customer_id=?, context_order_id=?, context_product_id=?, context_request_id=?,
                    context_checkout_key=?, context_occurred_at=?, updated_at=?
                WHERE ticket_id=? AND version=?
                """,
                t.version(), t.title(), t.message(), t.assigneeId(), t.status().name(),
                c.get("customerId"), c.get("orderId"), c.get("productId"), c.get("requestId"), c.get("checkoutKey"),
                c.get("occurredAt"), Timestamp.from(t.updatedAt()), t.ticketId(), expected) == 1;
    }

    private Ticket map(ResultSet row, int index) throws SQLException {
        Map<String, String> context = new LinkedHashMap<>();
        for (var column : Map.of("customerId", "context_customer_id", "orderId", "context_order_id",
                "productId", "context_product_id", "requestId", "context_request_id", "checkoutKey", "context_checkout_key").entrySet()) {
            String value = row.getString(column.getValue());
            if (value != null) context.put(column.getKey(), value);
        }
        // Preserve the normalized input precision for subsequent investigation snapshots.
        String occurred = row.getString("context_occurred_at");
        if (occurred != null) context.put("occurredAt", occurred);
        return new Ticket(row.getString("ticket_id"), row.getLong("version"), row.getString("title"),
                row.getString("message"), context, row.getString("assignee_id"),
                Ticket.Status.valueOf(row.getString("status")), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
