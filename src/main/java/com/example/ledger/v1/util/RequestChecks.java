package com.example.ledger.v1.util;

import java.util.Set;
import java.util.UUID;

import com.example.ledger.v1.exception.LedgerException;
import tools.jackson.databind.JsonNode;

public final class RequestChecks {
    private RequestChecks() {
    }

    public static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw LedgerException.invalid("Idempotency-Key", "invalid_key", "Supply a valid idempotency key.");
        }
    }

    public static UUID uuid(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw LedgerException.invalid(field, "invalid_" + (field.equals("accountId") ? "account_id" : "uuid"),
                    "Use a UUID.");
        }
        return UUID.fromString(value);
    }

    public static void fields(JsonNode body, String path, String... allowed) {
        if (!body.isObject()) {
            throw LedgerException.invalid(path, "invalid_body", "Provide a JSON object.");
        }
        var known = Set.of(allowed);
        for (String field : body.propertyNames()) {
            if (!known.contains(field)) {
                throw LedgerException.invalid(path.equals("body") ? field : path + "." + field,
                        "unknown_field", "This field is not supported.");
            }
        }
    }
}
