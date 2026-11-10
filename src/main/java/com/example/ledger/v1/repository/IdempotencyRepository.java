package com.example.ledger.v1.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Claim lock(String operation, byte[] key, byte[] fingerprint) {
        jdbc.sql("""
                INSERT INTO idempotency_requests (operation, request_key, fingerprint)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE resource_id = resource_id
                """).params(operation, key, fingerprint).update();
        return jdbc.sql("""
                SELECT fingerprint, resource_id FROM idempotency_requests
                WHERE operation = ? AND request_key = ? FOR UPDATE
                """).params(operation, key).query((rs, row) ->
                new Claim(rs.getBytes("fingerprint"), rs.getString("resource_id"))).single();
    }

    public void complete(String operation, byte[] key, String id) {
        jdbc.sql("""
                UPDATE idempotency_requests SET resource_id = ?
                WHERE operation = ? AND request_key = ?
                """).params(id, operation, key).update();
    }

    public record Claim(byte[] fingerprint, String resourceId) {
    }
}
