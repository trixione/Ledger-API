package com.example.ledger.v1.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Event> lockNext() {
        return jdbc.sql("""
                SELECT transaction_id, payload FROM transaction_outbox
                WHERE published_at IS NULL
                ORDER BY created_at, transaction_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((rs, row) -> new Event(rs.getString("transaction_id"), rs.getString("payload")))
                .optional();
    }

    public void markPublished(String transactionId) {
        jdbc.sql("UPDATE transaction_outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE transaction_id = ?")
                .param(transactionId).update();
    }

    public record Event(String transactionId, String payload) {
    }
}
