# MySQL schema and repository map

```mermaid
erDiagram
    ACCOUNTS ||--o{ LEDGER_ENTRIES : receives
    LEDGER_TRANSACTIONS ||--|{ LEDGER_ENTRIES : contains
    LEDGER_TRANSACTIONS o|--o| LEDGER_TRANSACTIONS : reverses
    LEDGER_TRANSACTIONS ||--o| SETTLED_PAYMENTS : records
    LEDGER_TRANSACTIONS ||--o| TRANSACTION_OUTBOX : announces

    ACCOUNTS {
        char id PK
        enum currency
        bigint balance_minor
        bigint overdraft_minor
    }
    LEDGER_TRANSACTIONS {
        char id PK
        enum currency
        varchar description
        timestamp recorded_at
        char reverses_id FK
    }
    LEDGER_ENTRIES {
        char transaction_id PK, FK
        int entry_number PK
        char account_id FK
        enum currency
        enum direction
        bigint amount_minor
    }
    IDEMPOTENCY_REQUESTS {
        varchar operation PK
        varbinary request_key PK
        binary fingerprint
        char resource_id
    }
    SETTLED_PAYMENTS {
        varbinary payment_id PK
        char transaction_id FK
    }
    TRANSACTION_OUTBOX {
        char transaction_id PK, FK
        json payload
        timestamp created_at
        timestamp published_at
    }
```

| Repository | Tables | Operations |
|---|---|---|
| `AccountRepository` | `accounts` | Insert at zero, read by ID, lock rows, update balances. |
| `IdempotencyRepository` | `idempotency_requests` | Claim and lock a key by operation, read fingerprint and result ID, complete claim. |
| `TransactionRepository` | `ledger_transactions`, `ledger_entries`, `settled_payments`, `transaction_outbox` | Read and insert immutable postings, check reversal links, record settled payment, enqueue announcement. |
| `OutboxRepository` | `transaction_outbox` | Lock the next pending event and mark it published after confirmation. |

Transaction and account IDs are UUIDs stored as strings. Composite entry foreign keys enforce that an entry has the same currency as both its transaction and its account. The idempotency result ID is a logical reference to an account or transaction according to the operation.

The database permits a transaction row to have no outbox row at the schema level. The posting service enforces at least two balanced entries and one outbox row within its database commit. See the [Flyway migration](../src/main/resources/db/migration/V1__ledger.sql).
