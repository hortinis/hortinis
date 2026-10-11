-- Fresh installations only. Reset existing synthetic technical databases before upgrading.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM technical_record)
        OR EXISTS (SELECT 1 FROM accepted_technical_record_operation)
        OR EXISTS (SELECT 1 FROM technical_record_change)
        OR EXISTS (SELECT 1 FROM technical_record_tombstone)
        OR EXISTS (SELECT 1 FROM retired_technical_record_identifier) THEN
        RAISE EXCEPTION 'Scope partitioning requires a fresh technical database.';
    END IF;
END $$;

DROP TABLE technical_record_change;
DROP TABLE accepted_technical_record_operation;
DROP TABLE technical_record;
DROP TABLE technical_record_tombstone;
DROP TABLE retired_technical_record_identifier;
DROP TABLE technical_synchronization_scope;

CREATE TABLE technical_server_installation (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    server_instance_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    access_mode TEXT CHECK (access_mode IN (
        'single-user-no-auth', 'single-user-auth', 'multi-user-auth'
    ))
);
INSERT INTO technical_server_installation (singleton) VALUES (TRUE);

CREATE TABLE technical_synchronization_scope (
    synchronization_scope_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_instance_id UUID NOT NULL
        REFERENCES technical_server_installation (server_instance_id),
    technical_default BOOLEAN NOT NULL DEFAULT FALSE,
    last_published_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_published_sequence >= 0)
);
-- Explicit sole-scope designation; initial access mode is committed during controlled startup.
CREATE UNIQUE INDEX technical_synchronization_scope_default
    ON technical_synchronization_scope (technical_default) WHERE technical_default;
INSERT INTO technical_synchronization_scope (server_instance_id, technical_default)
SELECT server_instance_id, TRUE FROM technical_server_installation;

CREATE TABLE technical_record (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    record_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    value TEXT NOT NULL,
    PRIMARY KEY (synchronization_scope_id, record_id)
);
CREATE TABLE accepted_technical_record_operation (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    operation_id UUID NOT NULL,
    operation_kind TEXT NOT NULL CHECK (operation_kind IN ('create', 'replace', 'delete')),
    record_id UUID NOT NULL,
    operation_value TEXT,
    expected_revision BIGINT CHECK (expected_revision > 0),
    PRIMARY KEY (synchronization_scope_id, operation_id),
    UNIQUE (synchronization_scope_id, operation_id, record_id),
    CHECK (
        (operation_kind = 'create' AND operation_value IS NOT NULL AND expected_revision IS NULL)
        OR
        (operation_kind = 'replace' AND operation_value IS NOT NULL AND expected_revision IS NOT NULL)
        OR
        (operation_kind = 'delete' AND operation_value IS NULL AND expected_revision IS NOT NULL)
    )
);
CREATE INDEX accepted_technical_record_operation_record
    ON accepted_technical_record_operation (synchronization_scope_id, record_id);

CREATE TABLE technical_record_change (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    server_sequence BIGINT NOT NULL CHECK (server_sequence > 0),
    operation_id UUID NOT NULL,
    record_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    value TEXT,
    change_kind TEXT NOT NULL CHECK (change_kind IN ('record', 'tombstone')),
    PRIMARY KEY (synchronization_scope_id, server_sequence),
    UNIQUE (synchronization_scope_id, operation_id),
    UNIQUE (synchronization_scope_id, record_id, revision),
    FOREIGN KEY (synchronization_scope_id, operation_id, record_id)
        REFERENCES accepted_technical_record_operation
            (synchronization_scope_id, operation_id, record_id),
    CHECK (
        (change_kind = 'record' AND value IS NOT NULL)
        OR
        (change_kind = 'tombstone' AND value IS NULL)
    )
);
CREATE TABLE technical_record_tombstone (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    record_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    deleted_at_sequence BIGINT NOT NULL CHECK (deleted_at_sequence > 0),
    PRIMARY KEY (synchronization_scope_id, record_id),
    UNIQUE (synchronization_scope_id, deleted_at_sequence)
);
-- Reservations outlive compactable journal, receipt, and tombstone rows.
CREATE TABLE retired_technical_record_identifier (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    record_id UUID NOT NULL,
    retired_at_sequence BIGINT NOT NULL CHECK (retired_at_sequence > 0),
    PRIMARY KEY (synchronization_scope_id, record_id),
    UNIQUE (synchronization_scope_id, retired_at_sequence)
);

-- Non-authorizing, expiring protocol observations; current access is resolved independently.
CREATE TABLE technical_exchange_expectation (
    synchronization_scope_id UUID NOT NULL
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    expectation TEXT NOT NULL CHECK (
        length(expectation) BETWEEN 1 AND 256 AND expectation ~ '^[A-Za-z0-9_-]+$'
    ),
    server_instance_id UUID NOT NULL
        REFERENCES technical_server_installation (server_instance_id),
    access_mode TEXT NOT NULL CHECK (access_mode IN (
        'single-user-no-auth', 'single-user-auth', 'multi-user-auth'
    )),
    account_id UUID,
    boundary_kind TEXT NOT NULL DEFAULT 'generationless' CHECK (boundary_kind = 'generationless'),
    phase TEXT NOT NULL CHECK (phase IN ('observed', 'confirmed', 'established')),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (synchronization_scope_id, expectation),
    CHECK ((access_mode = 'single-user-no-auth' AND account_id IS NULL)
        OR (access_mode <> 'single-user-no-auth' AND account_id IS NOT NULL))
);
CREATE INDEX technical_exchange_expectation_expiry ON technical_exchange_expectation (expires_at);
