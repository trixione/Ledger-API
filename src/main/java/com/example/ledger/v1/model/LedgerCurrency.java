package com.example.ledger.v1.model;

import java.math.BigDecimal;
import java.util.Currency;

import com.example.ledger.v1.exception.LedgerException;

public enum LedgerCurrency {
    EUR(2), USD(2), JPY(0);

    private final int scale;

    LedgerCurrency(int scale) {
        this.scale = scale;
    }

    public static LedgerCurrency parse(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw LedgerException.invalid("currency", "invalid_currency", "Use a three-letter ISO 4217 code.");
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException exception) {
            throw LedgerException.invalid("currency", "invalid_currency", "Unknown ISO 4217 currency.");
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw LedgerException.invalid("currency", "unsupported_currency", "Use EUR, USD or JPY.");
        }
    }

    public long overdraftMinor(String value) {
        if (value == null) {
            return 0;
        }
        return minorUnits(value, "overdraftLimit");
    }

    public long positiveMinor(String value, String field) {
        if (value == null) {
            throw LedgerException.invalid(field, "invalid_amount", "Provide a positive decimal string.");
        }
        long minor = minorUnits(value, field);
        if (minor == 0) {
            throw LedgerException.invalid(field, "invalid_amount", "Amount must be positive.");
        }
        return minor;
    }

    private long minorUnits(String value, String field) {
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?") || value.length() > 32) {
            throw LedgerException.invalid(field, "invalid_amount", "Use a nonnegative decimal string.");
        }
        BigDecimal amount = new BigDecimal(value);
        if (amount.scale() > scale) {
            throw LedgerException.invalid(field, "invalid_precision", "Too many decimal places.");
        }
        try {
            return amount.movePointRight(scale).longValueExact();
        } catch (ArithmeticException exception) {
            throw LedgerException.invalid(field, "amount_out_of_range", "Amount exceeds the supported range.");
        }
    }

    public String format(long minor) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
