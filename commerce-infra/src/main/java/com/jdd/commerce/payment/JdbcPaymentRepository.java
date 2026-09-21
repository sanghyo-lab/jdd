package com.jdd.commerce.payment;

import com.jdd.commerce.payment.domain.*;
import com.jdd.commerce.payment.port.PaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public JdbcPaymentRepository(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    private <T> Optional<Recorded<T>> operation(String orderId, String operation, String key, Class<T> type) {
        return jdbc.query("SELECT input_fingerprint, response_json FROM commerce.order_operations WHERE order_id=? AND operation=? AND request_key=?",
                (row, n) -> new Recorded<T>(row.getString("input_fingerprint"), json.readValue(row.getString("response_json"), type)),
                orderId, operation, key).stream().findFirst();
    }
    @Override public Optional<Recorded<PaymentResult>> paymentOperation(String orderId, String key) {
        return operation(orderId, "PAYMENT", key, PaymentResult.class);
    }
    @Override public Optional<Recorded<CancelResult>> cancelOperation(String orderId, String key) {
        return operation(orderId, "CANCEL", key, CancelResult.class);
    }
    private void record(String orderId, String operation, String key, String fingerprint, Object result, Instant at) {
        jdbc.update("INSERT INTO commerce.order_operations (order_id,operation,request_key,input_fingerprint,response_json,created_at) VALUES (?,?,?,?,?,?)",
                orderId, operation, key, fingerprint, json.writeValueAsString(result), Timestamp.from(at));
    }
    @Override public void recordPayment(String orderId, String key, String fingerprint, PaymentResult result, Instant at) {
        record(orderId, "PAYMENT", key, fingerprint, result, at);
    }
    @Override public void recordCancel(String orderId, String key, String fingerprint, CancelResult result, Instant at) {
        record(orderId, "CANCEL", key, fingerprint, result, at);
    }
    @Override public Optional<Payment> approvedPayment(String orderId) {
        return jdbc.query("SELECT * FROM commerce.payments WHERE order_id=? AND status='APPROVED' ORDER BY created_at,id LIMIT 1",
                this::payment, orderId).stream().findFirst();
    }
    @Override public Optional<Refund> refund(String orderId) {
        return jdbc.query("SELECT * FROM commerce.refunds WHERE order_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                this::refundRow, orderId).stream().findFirst();
    }
    private Payment payment(ResultSet row, int index) throws SQLException {
        return new Payment(row.getString("id"), row.getString("order_id"), row.getString("request_key"), row.getString("method"),
                row.getString("status"), row.getLong("amount"), row.getString("provider_reference"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    private Refund refundRow(ResultSet row, int index) throws SQLException {
        return new Refund(row.getString("id"), row.getString("order_id"), row.getString("payment_id"), row.getString("request_key"),
                row.getString("status"), row.getLong("amount"), row.getString("failure_code"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    @Override public void insertPayment(Payment p) {
        jdbc.update("INSERT INTO commerce.payments (id,order_id,request_key,method,status,amount,provider_reference,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                p.id(), p.orderId(), p.requestKey(), p.method(), p.status(), p.amount(), p.providerReference(),
                Timestamp.from(p.createdAt()), Timestamp.from(p.updatedAt()));
    }
    @Override public void insertRefund(Refund r) {
        jdbc.update("INSERT INTO commerce.refunds (id,order_id,payment_id,request_key,status,amount,failure_code,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                r.id(), r.orderId(), r.paymentId(), r.requestKey(), r.status(), r.amount(), r.failureCode(),
                Timestamp.from(r.createdAt()), Timestamp.from(r.updatedAt()));
    }
}
