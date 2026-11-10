package com.example.ledger.v1.model;

import java.math.BigDecimal;
import java.util.Currency;

public enum LedgerCurrency {
    EUR(2), USD(2), JPY(0);

    private final int scale;

    LedgerCurrency(int scale) {
        this.scale = scale;
    }

    public static LedgerCurrency parse(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw AccountError.invalid("currency", "invalid_currency", "Use a three-letter ISO 4217 code.");
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException exception) {
            throw AccountError.invalid("currency", "invalid_currency", "Unknown ISO 4217 currency.");
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw AccountError.invalid("currency", "unsupported_currency", "Use EUR, USD or JPY.");
        }
    }

    public long overdraftMinor(String value) {
        if (value == null) {
            return 0;
        }
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?") || value.length() > 32) {
            throw AccountError.invalid("overdraftLimit", "invalid_amount", "Use a nonnegative decimal string.");
        }
        BigDecimal amount = new BigDecimal(value);
        if (amount.scale() > scale) {
            throw AccountError.invalid("overdraftLimit", "invalid_precision", "Too many decimal places.");
        }
        try {
            return amount.movePointRight(scale).longValueExact();
        } catch (ArithmeticException exception) {
            throw AccountError.invalid("overdraftLimit", "amount_out_of_range", "Amount exceeds the supported range.");
        }
    }

    public String format(long minor) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
