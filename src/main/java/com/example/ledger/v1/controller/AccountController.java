package com.example.ledger.v1.controller;

import com.example.ledger.v1.model.Account;
import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.service.AccountService;

import java.net.URI;

import com.example.ledger.v1.util.RequestChecks;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<Account> open(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        RequestChecks.key(key);
        RequestChecks.fields(body, "body", "currency", "overdraftLimit");

        JsonNode currency = body.get("currency");
        if (currency == null || !currency.isString()) {
            throw LedgerException.invalid("currency", "invalid_currency", "Provide a currency string.");
        }
        JsonNode overdraft = body.get("overdraftLimit");
        if (overdraft != null && !overdraft.isString()) {
            throw LedgerException.invalid("overdraftLimit", "invalid_amount", "Provide a decimal string.");
        }
        Account account = accounts.open(key, currency.asString(),
                overdraft == null ? null : overdraft.asString());
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{accountId}")
    Account get(@PathVariable String accountId) {
        return accounts.get(RequestChecks.uuid(accountId, "accountId"));
    }
}
