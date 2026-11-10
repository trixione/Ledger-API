package com.example.ledger.v1.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.ledger.v1.model.LedgerCurrency;
import com.example.ledger.v1.model.LedgerTransaction;
import com.example.ledger.v1.model.LedgerTransaction.Direction;
import com.example.ledger.v1.model.LedgerTransaction.Entry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {
    private final JdbcClient jdbc;

    TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LedgerTransaction> find(UUID id, boolean lock) {
        String sql = """
                SELECT currency, description, recorded_at, reverses_id
                FROM ledger_transactions WHERE id = ?
                """;
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql).param(id.toString())
                .query((rs, row) -> {
                    String original = rs.getString("reverses_id");
                    return new Header(LedgerCurrency.valueOf(rs.getString("currency")),
                            rs.getString("description"), rs.getTimestamp("recorded_at").toInstant(),
                            original == null ? null : UUID.fromString(original));
                }).optional().map(header -> new LedgerTransaction(id, header.currency().name(),
                        header.description(), header.recordedAt(), header.reversesId(),
                        entries(id, header.currency())));
    }

    private List<Entry> entries(UUID id, LedgerCurrency currency) {
        return jdbc.sql("""
                SELECT account_id, direction, amount_minor FROM ledger_entries
                WHERE transaction_id = ? ORDER BY entry_number
                """).param(id.toString()).query((rs, row) -> new Entry(
                        UUID.fromString(rs.getString("account_id")),
                        Direction.valueOf(rs.getString("direction")),
                        currency.format(rs.getLong("amount_minor")))).list();
    }

    public boolean hasReversal(UUID original) {
        return jdbc.sql("SELECT id FROM ledger_transactions WHERE reverses_id = ? FOR UPDATE")
                .param(original.toString()).query(String.class).optional().isPresent();
    }

    public void insert(UUID id, LedgerCurrency currency, String description, UUID original) {
        jdbc.sql("""
                INSERT INTO ledger_transactions (id, currency, description, reverses_id)
                VALUES (?, ?, ?, ?)
                """).param(id.toString()).param(currency.name()).param(description)
                .param(original == null ? null : original.toString()).update();
    }

    public void insertEntry(UUID id, int number, LedgerCurrency currency, Entry entry, long minor) {
        jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, entry_number, account_id, currency, direction, amount_minor)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(id.toString(), number, entry.accountId().toString(), currency.name(),
                        entry.direction().name(), minor).update();
    }

    public void enqueue(UUID id, String payload) {
        jdbc.sql("INSERT INTO transaction_outbox (transaction_id, payload) VALUES (?, ?)")
                .params(id.toString(), payload).update();
    }

    public void recordPayment(byte[] paymentId, UUID id) {
        jdbc.sql("INSERT INTO settled_payments (payment_id, transaction_id) VALUES (?, ?)")
                .params(paymentId, id.toString()).update();
    }

    private record Header(LedgerCurrency currency, String description, Instant recordedAt, UUID reversesId) {
    }
}
