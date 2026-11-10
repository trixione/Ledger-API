package com.example.ledger.v1.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import com.example.ledger.v1.model.Account;
import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.model.LedgerCurrency;
import com.example.ledger.v1.repository.AccountRepository;
import com.example.ledger.v1.repository.IdempotencyRepository;

import com.example.ledger.v1.util.RequestFingerprint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;

    private final IdempotencyRepository idempotency;

    AccountService(AccountRepository accounts, IdempotencyRepository idempotency) {
        this.accounts = accounts;
        this.idempotency = idempotency;
    }

    @Transactional
    public Account open(String key, String currencyCode, String overdraftLimit) {
        LedgerCurrency currency = LedgerCurrency.parse(currencyCode);
        long limit = currency.overdraftMinor(overdraftLimit);
        byte[] fingerprint = RequestFingerprint.sha256(currency.name() + ":" + limit);
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);

        var claim = idempotency.lock("open_account", keyBytes, fingerprint);
        if (!MessageDigest.isEqual(claim.fingerprint(), fingerprint)) {
            throw new LedgerException(HttpStatus.CONFLICT, "idempotency_conflict", "Idempotency-Key",
                    "This key was used with different account details.");
        }

        String id = claim.resourceId();
        if (id == null) {
            id = UUID.randomUUID().toString();
            accounts.insert(id, currency, limit);
            idempotency.complete("open_account", keyBytes, id);
        }

        // The creation response always contains the opening balance, even on a later replay.
        return new Account(id, currency.name(), currency.format(limit), currency.format(0));
    }

    public Account get(UUID id) {
        var row = accounts.findById(id)
                .orElseThrow(() -> new LedgerException(HttpStatus.NOT_FOUND, "account_not_found", "accountId",
                        "No account exists with this ID."));
        LedgerCurrency currency = row.currency();
        Long limit = row.overdraftMinor();
        return new Account(row.id(), currency.name(), limit == null ? null : currency.format(limit),
                currency.format(row.balanceMinor()));
    }
}
