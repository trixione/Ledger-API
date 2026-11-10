package com.example.ledger.v1.exception;

import org.springframework.http.HttpStatus;

public final class LedgerException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    public LedgerException(HttpStatus status, String code, String field, String detail) {
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

    public static LedgerException invalid(String field, String code, String detail) {
        return new LedgerException(HttpStatus.BAD_REQUEST, code, field, detail);
    }
}
