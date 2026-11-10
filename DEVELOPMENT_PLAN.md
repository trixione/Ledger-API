# Ledger API development plan

Work through the tasks in order. Each row is one commit. Run its completion checks, review the staged diff, commit, then push to the configured remote. Tasks 1 through 9 cover the required scope.

## Current status

- **Task 1 implemented.** Maven Wrapper build and Spring startup smoke test passed. Full Compose startup and RabbitMQ health still need a Docker run.
- **Task 2 implemented.** The [REST contract](contracts/openapi.yaml) documents the account, transaction, and reversal endpoints. The [event schemas](contracts/events.yaml) define the incoming payment and outgoing transaction payloads.
- **Task 3 implemented and tested.** HTTP integration tests against MySQL 8.4 cover account reads, currency validation, settlement seeding, and concurrent retries across two application instances.
- **Tasks 4–6 implemented.** The API posts, reads, and reverses transactions. The RabbitMQ listener processes settled payments, and the sample sender publishes to the local broker.
- **Task 7 implemented.** The outbox publisher retries pending rows and marks them sent after broker confirmation and routing.
- **Task 8 implemented.** The integration suite covers cross-instance posting and replay against MySQL plus RabbitMQ redelivery, dead-letter routing, and outbox recovery.
- **Task 9 implemented locally.** Run `sh scripts/verify.sh` to verify a clean checkout with Docker Compose. Publish the verified commit to complete the handoff.

## Package structure

Keep versioned application code under `com.example.ledger.v1`. Put REST endpoints and HTTP validation in `controller`, business rules and transaction boundaries in `service`, JDBC queries and locking in `repository`, and domain and API data types in `model`. Keep RabbitMQ listeners and publishers in a sibling `v1.messaging` package. The payment listener calls the same posting service as the REST controller.

Keep the Spring Boot entry point at `com.example.ledger.LedgerApplication`, Flyway migrations in `src/main/resources/db/migration`, and contracts in `contracts/`. Mirror application package names in integration tests where applicable.

Keep REST exception mapping in `v1.advice`, shared exception types in `v1.exception`, and reusable request and fingerprint helpers in `v1.util`.

## Tasks

| Task | Work | Completion check | Commit message |
| --- | --- | --- | --- |
| 1. Bootstrap | Create the Java 17 / Spring Boot 4 service, Maven wrapper, Dockerfile and Compose setup for MySQL and RabbitMQ. Add a smoke test and link this plan and the architecture diagram from the README. | Build and start the stack. Confirm the service can connect to both dependencies. | `chore: bootstrap ledger service and Docker stack` |
| 2. Define contracts | Write OpenAPI for account creation/read and transaction creation/read/reversal. Define versioned resources, idempotency headers for all three writes, field-level errors, and event payloads. Payment events carry event ID, payment ID, merchant account ID, amount and currency. | Validate the contract and review example requests for success, retry, and rejection. | `docs: define ledger API and event contracts` |
| 3. Add persistence and accounts | Create migrations for accounts, transactions, entries, idempotency, payment deduplication and outbox. Seed unlimited-overdraft settlement accounts for EUR, USD and JPY. Open customer accounts at zero with a default overdraft limit of zero and an optional limit at creation. Store exact minor units (EUR/USD 2 decimals, JPY 0). Distinguish invalid ISO codes from valid but unsupported currencies. | Test supported currencies and precision, zero opening balance, and concurrent account creation retries against MySQL. | `feat: add ledger schema and account endpoints` |
| 4. Post transactions | Implement transaction create/read. Lock affected accounts in ascending ID order, aggregate deltas, require at least two positive, currency-precision entries on existing accounts in one currency, equal debit and credit totals, and balances within overdraft limits. Atomically write the immutable transaction and entries, balances, idempotency record and outbox. | Verify balanced multi-entry posting, distinct retry payload rejection and zero partial writes on refusal. | `feat: record balanced transactions atomically` |
| 5. Reverse transactions | Add the reversal endpoint. Flip entries and reuse posting checks. Enforce one reversal per original in MySQL and prevent reversal of a reversal. | Verify flipped entries, an unchanged original, overdraft refusal, and one winner when reversals race. | `feat: append linked transaction reversals` |
| 6. Consume settled payments | Configure RabbitMQ delivery and dead-lettering. Post settlement debit and merchant credit using the shared write path. Deduplicate by payment ID across event IDs and acknowledge after commit. Route malformed, unknown-account and wrong-currency events to a dead-letter queue without blocking subsequent payments. Add a sample-message sender. | Replay a payment with a different event ID and verify a single posting. Send an invalid message, then a valid one, and verify dead-lettering and continued processing. | `feat: record settled payments from RabbitMQ` |
| 7. Publish ledger events | Implement outbox delivery with broker confirms and retry. Include transaction details and a stable deduplication identifier. Document at-least-once delivery, possible duplicates, the absence of global ordering and recovery after a crash immediately following commit. | Verify direct postings, payments and reversals publish. Simulate interruption after commit and verify pending events are delivered after restart. | `feat: publish transaction events from the outbox` |
| 8. Prove concurrency and recovery | Add real MySQL and RabbitMQ integration scenarios across service instances. Cover opposite-direction transfers, competing debits, duplicate API requests, duplicate payments and failures around commit and acknowledgement. | Assert no lost updates, no overdraft breaches, per-currency balances summing to zero, and no partial writes on refusal. Verify all three write endpoints replay the original response across instances, including after a lost response or crash. Reject a reused key with a different payload. Verify all committed transactions have an announcement. | `test: verify ledger guarantees across service instances` |
| 9. Verify and document the handoff | Add a single script that builds, starts and tests the stack with Docker, Git and a JDK as the only host prerequisites. Finish the README with sample requests and messages, design choices, delivery semantics and an Improvements section. Document AI use and how the output was checked, and commit any instruction files provided to the AI. | Run the script from a clean checkout using the stated prerequisites. Follow the examples and verify the public repository contains the code, tests, README and commit history. | `docs: complete ledger challenge handoff` |

## Optional scope

Task 2 covers the API-first bonus. The other optional items are newest-first paginated account entries with the balance after each entry, historical balances, and correlation across request or payment logs and emitted events. Record any deferred items and production changes in the final README Improvements section.

## Commit and push each task

Run Git from this project folder. Create or connect the remote before the first push. Set up a new remote as private during development if desired. The challenge calls for a public repository at submission.

```text
git status --short
git diff --cached
git commit -m "<commit message from the plan>"
git push -u origin HEAD
```

Stage only the files for the completed task before reviewing the staged diff. After the first push sets the upstream, use `git push`. Keep each task commit in the final history.
