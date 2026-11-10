package com.example.ledger.v1.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.model.LedgerTransaction;
import com.example.ledger.v1.model.LedgerTransaction.Direction;
import com.example.ledger.v1.model.LedgerTransaction.Entry;
import com.example.ledger.v1.service.TransactionService;
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
@RequestMapping("/v1/transactions")
public class TransactionController {
    private final TransactionService transactions;

    TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<LedgerTransaction> post(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        RequestChecks.key(key);
        RequestChecks.fields(body, "body", "description", "entries");
        JsonNode description = body.path("description");
        if (!description.isString() || description.asString().codePointCount(0, description.asString().length()) > 255) {
            throw LedgerException.invalid("description", "invalid_description", "Provide a string up to 255 characters.");
        }
        JsonNode entries = body.path("entries");
        if (!entries.isArray() || entries.size() < 2) {
            throw LedgerException.invalid("entries", "invalid_entries", "Provide at least two entries.");
        }
        var postings = new ArrayList<Entry>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            postings.add(entry(entries.get(i), "entries[" + i + "]"));
        }
        return created(transactions.post(key, description.asString(), postings));
    }

    @GetMapping("/{transactionId}")
    LedgerTransaction get(@PathVariable String transactionId) {
        return transactions.get(RequestChecks.uuid(transactionId, "transactionId"));
    }

    @PostMapping("/{transactionId}/reversals")
    ResponseEntity<LedgerTransaction> reverse(@PathVariable String transactionId,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        RequestChecks.key(key);
        return created(transactions.reverse(key, RequestChecks.uuid(transactionId, "transactionId")));
    }

    private static Entry entry(JsonNode node, String field) {
        RequestChecks.fields(node, field, "accountId", "direction", "amount");
        UUID accountId = RequestChecks.uuid(node.path("accountId").isString()
                ? node.path("accountId").asString() : null, field + ".accountId");
        JsonNode direction = node.path("direction");
        if (!direction.isString() || !("DEBIT".equals(direction.asString()) || "CREDIT".equals(direction.asString()))) {
            throw LedgerException.invalid(field + ".direction", "invalid_direction", "Use DEBIT or CREDIT.");
        }
        JsonNode amount = node.path("amount");
        if (!amount.isString()) {
            throw LedgerException.invalid(field + ".amount", "invalid_amount", "Provide a decimal string.");
        }
        return new Entry(accountId, Direction.valueOf(direction.asString()), amount.asString());
    }

    private static ResponseEntity<LedgerTransaction> created(LedgerTransaction transaction) {
        return ResponseEntity.created(URI.create("/v1/transactions/" + transaction.id())).body(transaction);
    }
}
