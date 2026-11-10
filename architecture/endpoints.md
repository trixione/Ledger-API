# REST endpoints and database access

| Endpoint | Service | Database access and result |
|---|---|---|
| `POST /v1/accounts` | `AccountService.open` | Claims a key in `idempotency_requests`, inserts an `accounts` row at zero balance, and stores the new ID in the claim. Returns 201 and Location. |
| `GET /v1/accounts/{accountId}` | `AccountService.get` | Reads `accounts` and formats the current balance for its currency. Returns 200 or 404. |
| `POST /v1/transactions` | `TransactionService.post` | Claims a key, locks accounts, inserts the transaction, entries, and outbox event, then updates balances and completes the key claim. Returns 201 and Location. |
| `GET /v1/transactions/{transactionId}` | `TransactionService.get` | Reads `ledger_transactions` and `ledger_entries` in entry order. Returns 200 or 404. |
| `POST /v1/transactions/{transactionId}/reversals` | `TransactionService.reverse` | Locks the original, checks eligibility, and posts flipped entries through the shared flow. Returns 201 and Location. |
| `GET /actuator/health` | Spring Boot Actuator | Reports service health and probes MySQL and RabbitMQ connectivity. |

All three REST writes require `Idempotency-Key`. A matching replay returns the original result. A different valid request using the same key for the same operation returns 409. See the [OpenAPI contract](../contracts/openapi.yaml).

## Account creation and read

```mermaid
sequenceDiagram
    participant C as REST client
    participant A as AccountController
    participant S as AccountService
    participant M as MySQL through repositories
    C->>A: POST /v1/accounts with Idempotency-Key
    A->>S: Validate request and open account
    S->>M: Begin transaction and lock key claim
    alt New key
        S->>M: Insert account with zero balance and complete claim
        S->>M: Commit
        S-->>A: New account
    else Matching completed key
        S-->>A: Original account creation result
    else Different request using same key
        S->>M: Roll back
        S-->>A: Conflict
    end
    A-->>C: 201 with Location or 409
    C->>A: GET /v1/accounts/{accountId}
    A->>S: Read current account
    S->>M: Select accounts row by ID
    A-->>C: 200 with current balance or 404
```

## Posting and reversal

```mermaid
sequenceDiagram
    participant C as REST client
    participant T as TransactionController
    participant S as TransactionService
    participant M as MySQL through repositories
    C->>T: POST transaction or reversal with Idempotency-Key
    T->>S: Validate request shape and invoke service
    S->>M: Begin transaction and lock key claim
    alt Matching key already completed
        S->>M: Read recorded result
        S-->>T: Original transaction
        T-->>C: Replay 201 with the same ID
    else Same key with different request
        S->>M: Roll back
        T-->>C: 409 idempotency conflict
    else New request
        opt Reversal
            S->>M: Lock original and check reversal eligibility
            S->>S: Flip debit and credit entries
        end
        S->>M: Lock affected accounts in ascending ID order
        S->>S: Validate amount, currency, totals, and net balance per account
        alt Accepted
            S->>M: Insert transaction, entries, and outbox event
            S->>M: Update balances and complete key claim
            S->>M: Commit
            T-->>C: 201 with Location and recorded transaction
        else Refused
            S->>M: Roll back all changes
            T-->>C: Problem response with code and field
        end
    end
```

A reversal appends a new transaction linked by `reverses_id`. It preserves the original entries, observes overdraft limits, and cannot itself be reversed. A unique constraint prevents two reversals of the same transaction.

## Transaction read

```mermaid
sequenceDiagram
    participant C as REST client
    participant T as TransactionController
    participant S as TransactionService
    participant M as MySQL through TransactionRepository
    C->>T: GET /v1/transactions/{transactionId}
    T->>S: Read recorded transaction
    S->>M: Select transaction header by ID
    alt Found
        S->>M: Select entries ordered by entry_number
        T-->>C: 200 with recorded transaction and entries
    else Missing
        T-->>C: 404 transaction_not_found
    end
```
