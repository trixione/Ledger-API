package com.example.ledger.v1.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.model.LedgerCurrency;
import com.example.ledger.v1.model.LedgerTransaction;
import com.example.ledger.v1.model.LedgerTransaction.Direction;
import com.example.ledger.v1.model.LedgerTransaction.Entry;
import com.example.ledger.v1.repository.AccountRepository;
import com.example.ledger.v1.repository.AccountRepository.AccountRow;
import com.example.ledger.v1.repository.IdempotencyRepository;
import com.example.ledger.v1.repository.TransactionRepository;
import com.example.ledger.v1.util.RequestFingerprint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TransactionService {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final IdempotencyRepository idempotency;
    private final JsonMapper json;

    TransactionService(AccountRepository accounts, TransactionRepository transactions,
                       IdempotencyRepository idempotency, JsonMapper json) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerTransaction post(String key, String description, List<Entry> entries) {
        return once("post_transaction", key, json.writeValueAsString(new Posting(description, entries)),
                () -> postEntries(description, entries, null, null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerTransaction reverse(String key, UUID originalId) {
        return once("reverse_transaction", key, originalId.toString(), () -> {
            var original = find(originalId, true);
            if (original.reversesId() != null) {
                throw LedgerException.invalid("transactionId", "reversal_of_reversal", "A reversal cannot be reversed.");
            }
            if (transactions.hasReversal(originalId)) {
                throw new LedgerException(HttpStatus.CONFLICT, "already_reversed", "transactionId",
                        "This transaction already has a reversal.");
            }
            var flipped = original.entries().stream()
                    .map(entry -> new Entry(entry.accountId(), entry.direction().flipped(), entry.amount()))
                    .toList();
            return postEntries("Reversal of " + originalId, flipped, originalId, null);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settle(String paymentId, UUID merchant, String amount, LedgerCurrency currency) {
        UUID settlement = switch (currency) {
            case EUR -> UUID.fromString("00000000-0000-0000-0000-000000000001");
            case USD -> UUID.fromString("00000000-0000-0000-0000-000000000002");
            case JPY -> UUID.fromString("00000000-0000-0000-0000-000000000003");
        };
        String normalized = currency.format(currency.positiveMinor(amount, "amount"));
        var entries = List.of(new Entry(settlement, Direction.DEBIT, normalized),
                new Entry(merchant, Direction.CREDIT, normalized));
        once("settle_payment", paymentId, json.writeValueAsString(entries), () -> {
            if (merchant.equals(settlement)) {
                throw LedgerException.invalid("merchantAccountId", "invalid_merchant", "Use a merchant account.");
            }
            var transaction = postEntries("Settled payment " + paymentId, entries, null, currency);
            transactions.recordPayment(paymentId.getBytes(StandardCharsets.US_ASCII), transaction.id());
            return transaction;
        });
    }

    public LedgerTransaction get(UUID id) {
        return find(id, false);
    }

    private LedgerTransaction find(UUID id, boolean lock) {
        return transactions.find(id, lock).orElseThrow(() -> new LedgerException(
                HttpStatus.NOT_FOUND, "transaction_not_found", "transactionId", "Transaction does not exist."));
    }

    private LedgerTransaction once(String operation, String key, String request, Supplier<LedgerTransaction> write) {
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
        byte[] fingerprint = RequestFingerprint.sha256(request);
        var claim = idempotency.lock(operation, keyBytes, fingerprint);
        if (!java.security.MessageDigest.isEqual(claim.fingerprint(), fingerprint)) {
            throw new LedgerException(HttpStatus.CONFLICT, "idempotency_conflict", "Idempotency-Key",
                    "This key was used with a different request.");
        }
        if (claim.resourceId() != null) {
            return find(UUID.fromString(claim.resourceId()), false);
        }
        var result = write.get();
        idempotency.complete(operation, keyBytes, result.id().toString());
        return result;
    }

    private LedgerTransaction postEntries(String description, List<Entry> entries, UUID original,
                                          LedgerCurrency expectedCurrency) {
        var firstIndexes = new TreeMap<UUID, Integer>(Comparator.comparing(UUID::toString));
        for (int i = 0; i < entries.size(); i++) {
            firstIndexes.putIfAbsent(entries.get(i).accountId(), i);
        }
        var locked = new LinkedHashMap<UUID, AccountRow>();
        // MySQL stores canonical UUID strings. Acquire every lock in that same lexical order.
        firstIndexes.forEach((id, index) -> locked.put(id, accounts.lockById(id)
                .orElseThrow(() -> LedgerException.invalid("entries[" + index + "].accountId",
                        "unknown_account", "Account does not exist."))));

        LedgerCurrency currency = expectedCurrency == null ? locked.get(entries.get(0).accountId()).currency()
                : expectedCurrency;
        var deltas = new HashMap<UUID, BigInteger>();
        var amounts = new ArrayList<Long>(entries.size());
        BigInteger net = BigInteger.ZERO;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (locked.get(entry.accountId()).currency() != currency) {
                throw LedgerException.invalid("entries[" + i + "].accountId", "currency_mismatch",
                        "All accounts must use the transaction currency.");
            }
            long minor = currency.positiveMinor(entry.amount(), "entries[" + i + "].amount");
            amounts.add(minor);
            BigInteger signed = BigInteger.valueOf(entry.direction() == Direction.CREDIT ? minor : -minor);
            net = net.add(signed);
            deltas.merge(entry.accountId(), signed, BigInteger::add);
        }
        if (net.signum() != 0) {
            throw LedgerException.invalid("entries", "unbalanced_transaction", "Debits and credits must balance.");
        }

        var balances = new LinkedHashMap<UUID, Long>();
        locked.forEach((id, row) -> {
            String field = "entries[" + firstIndexes.get(id) + "].accountId";
            BigInteger balance = BigInteger.valueOf(row.balanceMinor()).add(deltas.get(id));
            if (row.overdraftMinor() != null && balance.compareTo(BigInteger.valueOf(-row.overdraftMinor())) < 0) {
                throw LedgerException.invalid(field, "overdraft_exceeded", "Resulting balance exceeds the overdraft limit.");
            }
            try {
                balances.put(id, balance.longValueExact());
            } catch (ArithmeticException exception) {
                throw LedgerException.invalid(field, "balance_out_of_range", "Resulting balance exceeds the supported range.");
            }
        });

        UUID id = UUID.randomUUID();
        transactions.insert(id, currency, description, original);
        for (int i = 0; i < entries.size(); i++) {
            transactions.insertEntry(id, i + 1, currency, entries.get(i), amounts.get(i));
        }
        balances.forEach(accounts::updateBalance);
        LedgerTransaction result = find(id, false);
        var payload = new RecordedEvent(id, id, result.currency(), result.description(),
                result.recordedAt(), original, result.entries());
        transactions.enqueue(id, json.writeValueAsString(payload));
        return result;
    }

    private record Posting(String description, List<Entry> entries) {
    }

    private record RecordedEvent(UUID eventId, UUID transactionId, String currency, String description,
                                 Instant recordedAt,
                                 @com.fasterxml.jackson.annotation.JsonInclude(
                                         com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID reversesId,
                                 List<Entry> entries) {
    }
}
