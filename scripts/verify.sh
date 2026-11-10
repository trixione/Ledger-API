#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
docker compose config --quiet
docker compose --profile test build ledger account-tests
# Pause the regular publisher while the recovery test controls delivery.
docker compose stop ledger
docker compose up -d --wait mysql rabbitmq
docker compose --profile test run --rm --no-deps account-tests
docker compose up -d --wait ledger
printf '%s\n' 'Verification passed. Ledger API is available at http://localhost:8080.'
