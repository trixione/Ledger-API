# Ledger API

The service opens accounts and reads their current balances. See the [architecture diagram](ledger-architecture.svg), [REST contract](contracts/openapi.yaml), and [event schemas](contracts/events.yaml) for the full design. Transaction posting, reversals, and message processing are planned for later tasks.

## Run locally

The build targets Java 17 and Spring Boot 4. Docker Compose starts the API, MySQL and RabbitMQ. The Docker build runs the Spring context smoke test using Maven inside the build image.

```powershell
docker compose config --quiet
docker compose up --build -d
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 5
```

Once the API has started, expect the overall status and both the `db` and `rabbit` components to report `UP`. Use `docker compose logs ledger` to inspect startup failures and `docker compose down` to stop the stack. Compose credentials are for local development.

See the [development plan](DEVELOPMENT_PLAN.md) for the task and commit sequence.

## Design decisions

The Java code for API version 1 lives under `com.example.ledger.v1`. The controller validates HTTP requests and returns field-level problem details. The service owns business rules and transaction boundaries. The repository owns JDBC queries, and model types describe accounts, currencies and errors. MySQL arbitrates idempotency claims across service instances.

Account creation accepts a single JSON object with unique, known fields. For retries, an omitted overdraft and an explicit zero are equivalent. Decimal strings with the same minor-unit value are also equivalent.

- The planned messaging layer will use RabbitMQ for incoming payment-settled and outgoing transaction-recorded messages. It will acknowledge valid payments after commit, retry transient failures, and route permanent failures to a dead-letter queue.
- Store amounts as integer minor units and validate input scale before conversion. Aggregate each account's signed entries before checking its resulting balance. The per-currency balance starts at zero and each committed transaction has equal debit and credit totals in a single currency.
- Lock affected accounts in ascending ID order before reading balances for writes. A reversal appends flipped entries, uses a unique constraint on the original transaction ID, and rejects originals that are themselves reversals. It applies the ordinary overdraft checks.
- Scope API idempotency keys to the operation. Clients share the key space until authentication is added, so generate a UUID per logical request. Account creation stores the normalized request fingerprint and account ID in the same transaction as the account. A matching retry reconstructs the original response with its zero opening balance, and a different valid request using the same key returns HTTP 409. Deduplicate settlement writes by payment ID so a different event ID cannot post the payment again.
- Commit the ledger write, balance updates, deduplication record and outbox record in one MySQL transaction. A publisher retries pending records after a crash and marks them sent after broker confirmation. Consumers may see duplicates and should deduplicate by transaction ID. Delivery has no global ordering guarantee.

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

## Verify

Run the Maven smoke test with a local JDK 17.

```powershell
.\mvnw.cmd verify
```

Run the HTTP account tests against MySQL in Compose. They exercise concurrent retries, conflicting key reuse, account reads, currency precision, and settlement seeding.

```powershell
docker compose --profile test run --build --rm account-tests
```

## AI usage

Codex helped scaffold the service, draft the contracts and migration, and implement and review the account endpoints. Verification includes the Maven build, Spring startup smoke test, and HTTP integration tests against MySQL 8.4.

## Improvements

Before production, authenticate callers and scope idempotency keys to the authenticated client. Transaction posting, reversals, and RabbitMQ processing are scheduled for their own implementation tasks.
