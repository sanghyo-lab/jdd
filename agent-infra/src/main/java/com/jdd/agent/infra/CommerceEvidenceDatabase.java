package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation.EvidenceType;
import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Separate SELECT account and repeatable-read snapshot; never uses the writable Agent DataSource. */
public final class CommerceEvidenceDatabase {
    private final String url;
    private final Properties credentials = new Properties();
    private final Semaphore connections = new Semaphore(2);
    private final Clock clock;

    public CommerceEvidenceDatabase(String url, String username, String password, Clock clock) {
        this.url = url;
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        credentials.setProperty("connectTimeout", "3");
        credentials.setProperty("socketTimeout", "5");
        this.clock = clock;
    }

    public <T> T snapshot(Function<Reader, T> operation) {
        boolean acquired = false;
        try {
            acquired = connections.tryAcquire(3, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("Evidence database is busy");
            try (Connection connection = DriverManager.getConnection(url, credentials)) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                verifyPrivileges(connection);
                return operation.apply(new Reader(connection));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Evidence query interrupted");
        } catch (SQLException unavailable) {
            throw new IllegalStateException("Evidence database query failed");
        } finally {
            if (acquired) connections.release();
        }
    }

    private static void verifyPrivileges(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole)
                   AND NOT has_schema_privilege(current_user, 'commerce', 'CREATE')
                   AND bool_and(has_table_privilege(current_user, 'commerce.' || t.name, 'SELECT'))
                   AND NOT bool_or(has_table_privilege(current_user, 'commerce.' || t.name,
                       'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) AS allowed
                FROM pg_roles r CROSS JOIN (VALUES ('products'), ('product_stock'), ('orders'), ('order_items'),
                    ('payments'), ('refunds'), ('coupons'), ('customer_coupons'), ('coupon_usages'), ('inventory_movements')) t(name)
                WHERE r.rolname = current_user GROUP BY r.rolsuper, r.rolcreatedb, r.rolcreaterole
                """)) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean("allowed"))
                    throw new IllegalStateException("Evidence database requires a separate SELECT-only account");
            }
        }
    }

    public final class Reader {
        private final Connection connection;
        private Reader(Connection connection) { this.connection = connection; }

        public Observation query(String table, String description, String sql, List<Object> parameters,
                                 int limit, String idColumn) {
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Evidence row limit must be 1..100");
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(3);
                statement.setMaxRows(limit + 1);
                for (int index = 0; index < parameters.size(); index++) {
                    Object value = parameters.get(index);
                    statement.setObject(index + 1, value instanceof Instant at ? at.atOffset(java.time.ZoneOffset.UTC) : value);
                }
                try (var result = statement.executeQuery()) {
                    var metadata = result.getMetaData();
                    var columns = new ArrayList<String>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) columns.add(metadata.getColumnLabel(index));
                    var rows = new ArrayList<Map<String, Object>>();
                    var ids = new ArrayList<String>();
                    boolean truncated = false;
                    while (result.next()) {
                        if (rows.size() == limit) { truncated = true; break; }
                        var row = new LinkedHashMap<String, Object>();
                        for (int index = 1; index <= columns.size(); index++) {
                            Object value = result.getObject(index);
                            if (value instanceof java.sql.Timestamp stamp) value = stamp.toInstant().toString();
                            if (value instanceof OffsetDateTime offset) value = offset.toInstant().toString();
                            row.put(columns.get(index - 1), value);
                        }
                        rows.add(row);
                        if (idColumn != null && row.get(idColumn) != null) ids.add(row.get(idColumn).toString());
                    }
                    return new Observation(EvidenceType.DATA, description + "; 반환 " + rows.size() + "행"
                            + (truncated ? " (일부 결과)" : ""), clock.instant(),
                            Map.of("schema", "commerce", "table", table, "recordIds", ids, "queryDescription", description),
                            Map.of("columns", columns, "rows", rows), truncated);
                }
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Evidence SELECT failed");
            }
        }
    }
}
