package com.example.ledger.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransaction(UUID id, String currency, String description, Instant recordedAt,
                                UUID reversesId, List<Entry> entries) {
    public enum Direction {
        DEBIT, CREDIT;

        public Direction flipped() {
            return this == DEBIT ? CREDIT : DEBIT;
        }
    }

    public record Entry(UUID accountId, Direction direction, String amount) {
    }
}
