# RabbitMQ listener and outbox publisher

## Payment settlement listener

```mermaid
sequenceDiagram
    participant P as Payment system
    participant R as RabbitMQ
    participant L as PaymentListener
    participant S as TransactionService
    participant M as MySQL
    P->>R: Publish payment.settled.v1 to ledger.payments
    R->>L: Deliver from ledger.payment-settled.v1
    L->>L: Validate event ID, payment ID, merchant, amount, currency
    alt Malformed payload
        L-->>R: Reject without requeue
        R->>R: Route to ledger.payment-settled.v1.dead
    else Valid payload shape
        L->>S: Settle payment
        S->>M: Lock payment ID claim in the settlement operation
        alt New payment passes ledger rules
            S->>M: Record settlement debit, merchant credit, and outbox row
            S->>M: Insert settled_payments and complete key claim
            S->>M: Commit
            L-->>R: Acknowledge after commit
        else Matching payment ID and details
            S->>M: Read existing result
            L-->>R: Acknowledge without another posting
        else Payment fails ledger rules or conflicts with claim
            S->>M: Roll back
            L-->>R: Reject without requeue
            R->>R: Route to ledger.payment-settled.v1.dead
        end
    end
```

The payment ID identifies the ledger effect. Redelivery with a different event ID and the same payment details replays that effect. Unexpected processing failures are eligible for requeue.

## Transaction announcement publisher

```mermaid
sequenceDiagram
    participant S as TransactionService
    participant M as MySQL transaction_outbox
    participant W as OutboxPublisher
    participant R as RabbitMQ
    S->>M: Insert event in the ledger commit
    W->>M: Lock pending row with FOR UPDATE SKIP LOCKED
    W->>R: Publish persistent transaction.recorded.v1 to ledger.transactions
    alt Broker confirms and message is routed
        R-->>W: ACK without a return
        W->>M: Set published_at and commit
    else Failure, timeout, or returned message
        W->>M: Roll back and leave row pending
        Note over W,M: A later poll retries it
    end
```

The default durable queue is `ledger.transaction-recorded.v1`. Direct postings, reversals, and payments each create an announcement. A crash after broker confirmation and before updating MySQL can produce a duplicate. Deduplicate by `eventId`, which equals the transaction ID. Delivery has no global ordering guarantee. See the [event schemas](../contracts/events.yaml).
