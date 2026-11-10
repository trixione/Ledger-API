package com.example.ledger.v1.model;

import org.springframework.http.HttpStatus;

public final class AccountError extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    public AccountError(HttpStatus status, String code, String field, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    public static AccountError invalid(String field, String code, String detail) {
        return new AccountError(HttpStatus.BAD_REQUEST, code, field, detail);
    }
}
