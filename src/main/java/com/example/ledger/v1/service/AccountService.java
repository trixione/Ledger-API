package com.example.ledger.v1.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import com.example.ledger.v1.model.Account;
import com.example.ledger.v1.model.AccountError;
import com.example.ledger.v1.model.LedgerCurrency;
import com.example.ledger.v1.repository.AccountRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account open(String key, String currencyCode, String overdraftLimit) {
        LedgerCurrency currency = LedgerCurrency.parse(currencyCode);
        long limit = currency.overdraftMinor(overdraftLimit);
        byte[] fingerprint = fingerprint(currency.name() + ":" + limit);
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);

        var claim = accounts.lockCreationClaim(keyBytes, fingerprint);
        if (!MessageDigest.isEqual(claim.fingerprint(), fingerprint)) {
            throw new AccountError(HttpStatus.CONFLICT, "idempotency_conflict", "Idempotency-Key",
                    "This key was used with different account details.");
        }

        String id = claim.resourceId();
        if (id == null) {
            id = UUID.randomUUID().toString();
            accounts.insert(id, currency, limit);
            accounts.completeCreationClaim(keyBytes, id);
        }

        // The creation response always contains the opening balance, even on a later replay.
        return new Account(id, currency.name(), currency.format(limit), currency.format(0));
    }

    public Account get(UUID id) {
        var row = accounts.findById(id)
                .orElseThrow(() -> new AccountError(HttpStatus.NOT_FOUND, "account_not_found", "accountId",
                        "No account exists with this ID."));
        LedgerCurrency currency = row.currency();
        Long limit = row.overdraftMinor();
        return new Account(row.id(), currency.name(), limit == null ? null : currency.format(limit),
                currency.format(row.balanceMinor()));
    }

    private static byte[] fingerprint(String canonicalRequest) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
