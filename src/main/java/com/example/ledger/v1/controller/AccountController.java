package com.example.ledger.v1.controller;

import com.example.ledger.v1.model.Account;
import com.example.ledger.v1.model.AccountError;
import com.example.ledger.v1.service.AccountService;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
        if (key == null || !key.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw AccountError.invalid("Idempotency-Key", "invalid_key", "Supply a valid idempotency key.");
        }
        if (!body.isObject()) {
            throw AccountError.invalid("body", "invalid_body", "Provide a JSON object.");
        }

        for (String field : body.propertyNames()) {
            if (!field.equals("currency") && !field.equals("overdraftLimit")) {
                throw AccountError.invalid(field, "unknown_field", "This field is not supported.");
            }
        }

        JsonNode currency = body.get("currency");
        if (currency == null || !currency.isString()) {
            throw AccountError.invalid("currency", "invalid_currency", "Provide a currency string.");
        }
        JsonNode overdraft = body.get("overdraftLimit");
        if (overdraft != null && !overdraft.isString()) {
            throw AccountError.invalid("overdraftLimit", "invalid_amount", "Provide a decimal string.");
        }
        Account account = accounts.open(key, currency.asString(),
                overdraft == null ? null : overdraft.asString());
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{accountId}")
    Account get(@PathVariable String accountId) {
        if (!accountId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw AccountError.invalid("accountId", "invalid_account_id", "Use a UUID.");
        }
        return accounts.get(UUID.fromString(accountId));
    }

    @ExceptionHandler(AccountError.class)
    ProblemDetail accountError(AccountError error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setProperty("code", error.code());
        problem.setProperty("field", error.field());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformedBody() {
        return accountError(new AccountError(HttpStatus.BAD_REQUEST, "invalid_body", "body",
                "Provide a valid JSON request body."));
    }
}
