CREATE TABLE accounts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    currency ENUM('EUR', 'USD', 'JPY') NOT NULL,
    overdraft_minor BIGINT NULL CHECK (overdraft_minor >= 0),
    balance_minor BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, currency),
    CHECK (overdraft_minor IS NULL OR balance_minor >= -overdraft_minor)
) ENGINE=InnoDB;

CREATE TABLE ledger_transactions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    currency ENUM('EUR', 'USD', 'JPY') NOT NULL,
    description VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reverses_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,
    UNIQUE (id, currency),
    FOREIGN KEY (reverses_id, currency) REFERENCES ledger_transactions (id, currency)
) ENGINE=InnoDB;

CREATE TABLE ledger_entries (
    transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entry_number INT NOT NULL CHECK (entry_number > 0),
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency ENUM('EUR', 'USD', 'JPY') NOT NULL,
    direction ENUM('DEBIT', 'CREDIT') NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (transaction_id, entry_number),
    FOREIGN KEY (transaction_id, currency) REFERENCES ledger_transactions (id, currency),
    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
) ENGINE=InnoDB;

CREATE TABLE idempotency_requests (
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARBINARY(128) NOT NULL,
    fingerprint BINARY(32) NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    PRIMARY KEY (operation, request_key)
) ENGINE=InnoDB;

CREATE TABLE settled_payments (
    payment_id VARBINARY(128) PRIMARY KEY,
    transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id)
) ENGINE=InnoDB;

CREATE TABLE transaction_outbox (
    transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    payload JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    INDEX (published_at, created_at),
    FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id)
) ENGINE=InnoDB;

INSERT INTO accounts (id, currency, overdraft_minor) VALUES
    ('00000000-0000-0000-0000-000000000001', 'EUR', NULL),
    ('00000000-0000-0000-0000-000000000002', 'USD', NULL),
    ('00000000-0000-0000-0000-000000000003', 'JPY', NULL);
