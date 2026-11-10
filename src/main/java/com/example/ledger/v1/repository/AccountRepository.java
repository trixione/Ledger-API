package com.example.ledger.v1.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.ledger.v1.model.LedgerCurrency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Claim lockCreationClaim(byte[] key, byte[] fingerprint) {
        // A concurrent retry waits for the first insert to commit or roll back.
        jdbc.sql("""
                INSERT INTO idempotency_requests (operation, request_key, fingerprint)
                VALUES ('open_account', ?, ?)
                ON DUPLICATE KEY UPDATE resource_id = resource_id
                """).params(key, fingerprint).update();

        return jdbc.sql("""
                SELECT fingerprint, resource_id FROM idempotency_requests
                WHERE operation = 'open_account' AND request_key = ? FOR UPDATE
                """).param(key).query((rs, row) ->
                new Claim(rs.getBytes("fingerprint"), rs.getString("resource_id"))).single();
    }

    public void insert(String id, LedgerCurrency currency, long limit) {
        jdbc.sql("INSERT INTO accounts (id, currency, overdraft_minor) VALUES (?, ?, ?)")
                .params(id, currency.name(), limit).update();
    }

    public void completeCreationClaim(byte[] key, String id) {
        jdbc.sql("""
                UPDATE idempotency_requests SET resource_id = ?
                WHERE operation = 'open_account' AND request_key = ?
                """).params(id, key).update();
    }

    public Optional<AccountRow> findById(UUID id) {
        return jdbc.sql("SELECT id, currency, overdraft_minor, balance_minor FROM accounts WHERE id = ?")
                .param(id.toString())
                .query((rs, row) -> new AccountRow(
                        rs.getString("id"),
                        LedgerCurrency.valueOf(rs.getString("currency")),
                        rs.getObject("overdraft_minor", Long.class),
                        rs.getLong("balance_minor")))
                .optional();
    }

    public record Claim(byte[] fingerprint, String resourceId) {
    }

    public record AccountRow(String id, LedgerCurrency currency, Long overdraftMinor, long balanceMinor) {
    }
}
