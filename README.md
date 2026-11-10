# Ledger API

The service opens accounts, reads balances, records and reverses transactions, consumes settled payments, and publishes recorded transaction events. See the [architecture diagram](architecture/ledger-architecture.svg), [REST contract](contracts/openapi.yaml), and [event schemas](contracts/events.yaml).

## Run locally

The build targets Java 17 and Spring Boot 4. Docker Compose starts the API, MySQL and RabbitMQ. The Docker build runs the Spring context smoke test using Maven inside the build image.

```powershell
docker compose config --quiet
docker compose up --build -d
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 5
```

Once the API has started, expect the overall status and both the `db` and `rabbit` components to report `UP`. Use `docker compose logs ledger` to inspect startup failures and `docker compose down` to stop the stack. Compose credentials are for local development.

See the [development plan](DEVELOPMENT_PLAN.md) for the task and commit sequence.

The [challenge test guide](docs/Ledger%20API%20Challenge%20Test%20Guide.docx) contains copy-ready requests, expected results, and the submission checklist.

The [architecture diagrams](architecture/README.md) trace the REST endpoints, RabbitMQ flows, and MySQL table access.

## Design decisions

The Java code for API version 1 lives under `com.example.ledger.v1`. Controllers handle HTTP requests. Services own business rules and transaction boundaries, repositories own JDBC queries, and models describe accounts, currencies, and transactions. MySQL arbitrates idempotency claims across service instances.

Account creation accepts a single JSON object with unique, known fields. For retries, an omitted overdraft and an explicit zero are equivalent. Decimal strings with the same minor-unit value are also equivalent.

The `v1.advice` package maps exceptions to REST problem responses. Shared exceptions live in `v1.exception`, and request validation and fingerprint helpers live in `v1.util`.

- The RabbitMQ listener acknowledges valid payments after commit, requeues processing failures, and routes invalid payments to a dead-letter queue.
- Store amounts as integer minor units and validate input scale before conversion. Aggregate each account's signed entries before checking its resulting balance. The per-currency balance starts at zero and each committed transaction has equal debit and credit totals in a single currency.
- Lock affected accounts in ascending ID order before reading balances for writes. A reversal appends flipped entries, uses a unique constraint on the original transaction ID, and rejects originals that are themselves reversals. It applies the ordinary overdraft checks.
- Scope API idempotency keys to the operation. Clients share the key space until authentication is added, so generate a UUID per logical request. Account creation stores the normalized request fingerprint and account ID in the same transaction as the account. A matching retry reconstructs the original response with its zero opening balance, and a different valid request using the same key returns HTTP 409. Deduplicate settlement writes by payment ID so a different event ID cannot post the payment again.
- The ledger write, balance updates, deduplication record and outbox record commit in one MySQL transaction. Publishers claim pending rows with `FOR UPDATE SKIP LOCKED` so multiple instances can drain the outbox.

## Accounts

```powershell
$key = [guid]::NewGuid().ToString()
$account = Invoke-RestMethod http://localhost:8080/v1/accounts -Method Post `
  -Headers @{'Idempotency-Key' = $key} -ContentType application/json `
  -Body '{"currency":"EUR","overdraftLimit":"25.50"}'
Invoke-RestMethod "http://localhost:8080/v1/accounts/$($account.id)"
```

Repeat the POST with the same key and body to receive the same HTTP 201, Location, and response body. Send amounts as decimal strings. Omitting the overdraft limit defaults it to zero. Only the seeded settlement accounts have an unlimited overdraft, represented as null in the account response.

Flyway applies the MySQL migrations at startup and seeds the EUR, USD, and JPY settlement accounts.

## Transactions and reversals

After creating an EUR account as above, post a balanced transaction and reverse it:

```powershell
$entries = @(
  @{accountId='00000000-0000-0000-0000-000000000001'; direction='DEBIT'; amount='125.50'}
  @{accountId=$account.id; direction='CREDIT'; amount='125.50'}
)
$transaction = Invoke-RestMethod http://localhost:8080/v1/transactions -Method Post `
  -Headers @{'Idempotency-Key' = [guid]::NewGuid().ToString()} -ContentType application/json `
  -Body (@{description='Example posting'; entries=$entries} | ConvertTo-Json -Depth 4)
Invoke-RestMethod "http://localhost:8080/v1/transactions/$($transaction.id)"
Invoke-RestMethod "http://localhost:8080/v1/transactions/$($transaction.id)/reversals" -Method Post `
  -Headers @{'Idempotency-Key' = [guid]::NewGuid().ToString()}
```

When retrying, reuse the original key. For direct postings, preserve the description, entry order, and amount strings. For a reversal, reuse the same original transaction ID. Reusing a key for a different request returns HTTP 409.

## Settled payments

With the Compose stack running, send a sample payment for the EUR account and then resend it with the same payment ID. Each invocation generates a fresh event ID.

```powershell
$sent = .\scripts\send-payment.ps1 -MerchantAccountId $account.id
.\scripts\send-payment.ps1 -MerchantAccountId $account.id -PaymentId $sent.PaymentId
```

The sender publishes to the `ledger.payments` exchange with routing key `payment.settled.v1`. The script reports whether RabbitMQ routed the message. Read the merchant account to confirm processing. Invalid payloads can be inspected in `ledger.payment-settled.v1.dead` through the local RabbitMQ management UI on port 15672.

## Transaction announcements

Every committed posting, including reversals and settled payments, creates an outbox event. The publisher sends persistent JSON messages to the topic exchange `ledger.transactions` with routing key `transaction.recorded.v1`. The durable queue `ledger.transaction-recorded.v1` is bound by default. Each independent subscriber should bind its own durable queue before processing traffic.

Delivery to the broker is at least once while MySQL and RabbitMQ recover with their durable data intact. A row is marked published only after a broker acknowledgement and successful routing. A crash after the ledger commit leaves a pending row for another publisher to pick up. A crash after broker confirmation but before the database update commits can produce a duplicate. Deduplicate by the stable `eventId`, which equals the transaction ID. Multiple publishers and retries can change delivery order. A subscriber that binds a queue later does not receive messages published before the binding.

## Verify

With Docker Compose, Git, and a JDK 17 available, run this from the project root. On Windows, use Git Bash.

```sh
sh scripts/verify.sh
```

Run the script against the local development stack. It builds both images, stops any running API container so the recovery test controls publishing, starts MySQL and RabbitMQ, and writes test records to the local database. After the tests pass, it starts the API and waits for its health check. Stop the stack with `docker compose down`.

For a quick Maven smoke test with a local JDK 17:

```powershell
.\mvnw.cmd verify
```

The integration suite exercises concurrent writes across two service instances, replay, rollback on refusal, per-currency integrity, live RabbitMQ dead-letter routing, and outbox recovery with a restarted publisher.

## AI usage

Codex helped scaffold the service, draft the contracts and migration, implement the ledger and messaging flows, and write the integration tests. Verification uses the Maven build, a Spring startup smoke test, and integration tests against MySQL and RabbitMQ.

## Improvements

Before production, authenticate callers and scope idempotency keys to the authenticated client. Add outbox backlog metrics and a retention policy for published rows. Configure broker queue replication and monitor dead-letter queues. Paginated account entries, historical balances, and end-to-end correlation were deferred to focus on the core ledger guarantees.
