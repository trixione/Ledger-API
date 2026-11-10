# Application overview

```mermaid
flowchart LR
    Client["REST client"] --> AC["AccountController"]
    Client --> TC["TransactionController"]
    AC --> AS["AccountService"]
    TC --> TS["TransactionService"]
    Pay["Payment system"] --> In["RabbitMQ exchange: ledger.payments"]
    In --> Q["Queue: ledger.payment-settled.v1"]
    Q --> PL["PaymentListener"]
    PL --> TS
    Q -->|Rejected payment via dead-letter exchange| DLQ["Queue: ledger.payment-settled.v1.dead"]
    AS --> AR["AccountRepository"]
    AS --> IR["IdempotencyRepository"]
    TS --> AR
    TS --> IR
    TS --> TR["TransactionRepository"]
    AR --> DB[("MySQL")]
    IR --> DB
    TR --> DB
    OP["OutboxPublisher"] --> OR["OutboxRepository"]
    OR --> DB
    OP --> Out["RabbitMQ exchange: ledger.transactions"]
    Out --> OQ["Queue: ledger.transaction-recorded.v1"]
    OQ --> Consumer["Downstream consumer"]
```

Controllers validate HTTP inputs. Services enforce ledger rules and own transaction boundaries. Repositories use Spring JDBC to access MySQL. `LedgerControllerAdvice` maps REST errors to problem responses with a status, code, and field. The listener maps invalid messages to broker rejections.
