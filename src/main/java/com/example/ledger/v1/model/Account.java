package com.example.ledger.v1.model;

public record Account(String id, String currency, String overdraftLimit, String balance) {
}
