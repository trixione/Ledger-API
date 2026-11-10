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

    public void insert(String id, LedgerCurrency currency, long limit) {
        jdbc.sql("INSERT INTO accounts (id, currency, overdraft_minor) VALUES (?, ?, ?)")
                .params(id, currency.name(), limit).update();
    }

    public Optional<AccountRow> findById(UUID id) {
        return find(id, false);
    }

    public Optional<AccountRow> lockById(UUID id) {
        return find(id, true);
    }

    public void updateBalance(UUID id, long balance) {
        jdbc.sql("UPDATE accounts SET balance_minor = ? WHERE id = ?")
                .params(balance, id.toString()).update();
    }

    private Optional<AccountRow> find(UUID id, boolean lock) {
        String sql = "SELECT id, currency, overdraft_minor, balance_minor FROM accounts WHERE id = ?";
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .param(id.toString())
                .query((rs, row) -> new AccountRow(
                        rs.getString("id"),
                        LedgerCurrency.valueOf(rs.getString("currency")),
                        rs.getObject("overdraft_minor", Long.class),
                        rs.getLong("balance_minor")))
                .optional();
    }

    public record AccountRow(String id, LedgerCurrency currency, Long overdraftMinor, long balanceMinor) {
    }
}
