package com.example.ledger.v1.advice;

import com.example.ledger.v1.controller.AccountController;

import com.example.ledger.v1.exception.LedgerException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AccountController.class)
class LedgerControllerAdvice {
    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledgerError(LedgerException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setProperty("code", error.code());
        problem.setProperty("field", error.field());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformedBody() {
        return ledgerError(LedgerException.invalid("body", "invalid_body",
                "Provide a valid JSON request body."));
    }
}
