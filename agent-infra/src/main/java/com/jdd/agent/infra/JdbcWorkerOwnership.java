package com.jdd.agent.infra;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** One PostgreSQL session owns startup recovery and dispatch for this local Agent installation. */
@Component
public final class JdbcWorkerOwnership implements AutoCloseable {
    private static final long LOCK_ID = 0x4a44444147454e54L;
    private final DataSource dataSource;
    private Connection owner;

    public JdbcWorkerOwnership(DataSource dataSource) { this.dataSource = dataSource; }

    public synchronized boolean acquire() throws SQLException {
        if (owner != null) return valid();
        Connection candidate = dataSource.getConnection();
        boolean acquired = false;
        try (var statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setQueryTimeout(3);
            statement.setLong(1, LOCK_ID);
            try (var result = statement.executeQuery()) {
                acquired = result.next() && result.getBoolean(1);
            }
            if (acquired) owner = candidate;
            return acquired;
        } catch (SQLException uncertain) {
            try { candidate.abort(Runnable::run); } catch (SQLException ignored) { }
            throw uncertain;
        } finally {
            if (!acquired) candidate.close();
        }
    }

    public synchronized boolean valid() throws SQLException { return owner != null && owner.isValid(2); }

    @Override public synchronized void close() {
        if (owner == null) return;
        try (var statement = owner.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setQueryTimeout(3);
            statement.setLong(1, LOCK_ID);
            statement.execute();
        } catch (SQLException lost) {
            // Never return a connection with an uncertain session lock to the pool.
            try { owner.abort(Runnable::run); } catch (SQLException ignored) { }
        } finally {
            try { owner.close(); } catch (SQLException ignored) { }
            owner = null;
        }
    }
}
