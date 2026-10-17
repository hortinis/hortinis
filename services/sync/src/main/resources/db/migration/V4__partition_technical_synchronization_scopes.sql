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


-- S9-R fresh-install email identity. Production admission remains closed during replacement.
CREATE TABLE authentication_account (
    account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    UNIQUE (account_id, server_instance_id),
    server_instance_id UUID NOT NULL
        REFERENCES technical_server_installation (server_instance_id),
    synchronization_scope_id UUID NOT NULL UNIQUE
        REFERENCES technical_synchronization_scope (synchronization_scope_id),
    delivery_email VARCHAR(254) COLLATE "C" NOT NULL,
    canonical_email VARCHAR(254) COLLATE "C" NOT NULL UNIQUE
        CHECK (canonical_email = lower(delivery_email COLLATE "C")),
    CHECK (octet_length(delivery_email) <= 254 AND delivery_email ~
        '^[A-Za-z0-9!#$%&''*+/=?^_`{|}~.-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$'),
    status TEXT NOT NULL CHECK (status IN ('pending', 'active', 'disabled')),
    security_version BIGINT NOT NULL DEFAULT 1 CHECK (security_version > 0),
    password_verifier VARCHAR(256)
        CHECK (password_verifier LIKE '{argon2id}$argon2id$v=19$m=65536,t=3,p=1$%'),
    created_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    CHECK (status <> 'pending' OR (password_verifier IS NULL AND verified_at IS NULL))
);

-- Action secrets never enter JDBC: only their SHA-256 verifier is retained.
CREATE TABLE authentication_action_token (
    account_id UUID NOT NULL,
    server_instance_id UUID NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose IN ('invitation', 'reset')),
    secret_verifier CHAR(64) NOT NULL UNIQUE CHECK (secret_verifier ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, purpose),
    FOREIGN KEY (account_id, server_instance_id)
        REFERENCES authentication_account (account_id, server_instance_id) ON DELETE CASCADE,
    CHECK (expires_at > created_at AND expires_at <= created_at +
        CASE purpose WHEN 'invitation' THEN INTERVAL '24 hours' ELSE INTERVAL '10 minutes' END)
);
CREATE INDEX authentication_action_token_expiry ON authentication_action_token (expires_at);
CREATE INDEX authentication_pending_account_expiry ON authentication_account (created_at)
    WHERE status = 'pending';

CREATE TABLE authentication_credential (
    credential_id BYTEA PRIMARY KEY CHECK (octet_length(credential_id) BETWEEN 1 AND 1536),
    account_id UUID NOT NULL REFERENCES authentication_account (account_id) ON DELETE CASCADE,
    public_key_cose BYTEA NOT NULL CHECK (octet_length(public_key_cose) BETWEEN 1 AND 2048),
    algorithm INTEGER NOT NULL CHECK (algorithm IN (-7, -257, -8)),
    signature_count BIGINT NOT NULL CHECK (signature_count BETWEEN 0 AND 4294967295),
    backup_eligible BOOLEAN NOT NULL,
    backed_up BOOLEAN NOT NULL,
    label VARCHAR(64),
    CHECK (NOT backed_up OR backup_eligible)
);
CREATE INDEX authentication_credential_account ON authentication_credential (account_id);

-- Pending ceremonies and password/full authentication are stored only in Spring Session.
CREATE TABLE authentication_challenge (
    session_reference UUID PRIMARY KEY,
    account_id UUID REFERENCES authentication_account (account_id) ON DELETE CASCADE,
    challenge BYTEA NOT NULL CHECK (octet_length(challenge) = 32),
    purpose TEXT NOT NULL CHECK (purpose IN (
        'setup', 'login', 'step-up', 'credential', 'recover-password', 'recover-passkey', 'operator'
    )),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recovery_code_verifier CHAR(64) CHECK (recovery_code_verifier ~ '^[a-f0-9]{64}$'),
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '5 minutes')
);
CREATE INDEX authentication_challenge_expiry ON authentication_challenge (expires_at);

CREATE TABLE authentication_recovery_code (
    account_id UUID NOT NULL REFERENCES authentication_account (account_id) ON DELETE CASCADE,
    secret_verifier CHAR(64) NOT NULL CHECK (secret_verifier ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, secret_verifier),
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '8760 hours')
);
CREATE INDEX authentication_recovery_code_expiry ON authentication_recovery_code (expires_at);

-- Protected security events contain only closed codes, trusted UTC and a fresh request reference.
-- They deliberately have no account/session/source address/credential mapping or arbitrary payload.
CREATE TABLE authentication_security_event (
    request_reference UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    event TEXT NOT NULL CHECK (event IN (
        'setup', 'login', 'recovery', 'step-up', 'credential-change', 'session-revoke', 'logout'
    )),
    outcome TEXT NOT NULL CHECK (outcome IN ('succeeded', 'denied', 'unavailable')),
    reason TEXT NOT NULL CHECK (reason IN (
        'completed', 'invalid-proof', 'expired', 'precondition', 'capacity', 'unavailable'
    ))
);
CREATE INDEX authentication_security_event_retention ON authentication_security_event (occurred_at);

-- Spring Session JDBC 4.1.1 PostgreSQL layout, with SESSION_ID widened for the selected independent
-- 32-byte/43-character session generator. PRIMARY_ID remains the framework's non-secret row key.
-- Attributes are encoded through the dedicated allowlisted converter, not Java object serialization.
CREATE TABLE spring_session (
    primary_id CHAR(36) PRIMARY KEY,
    session_id VARCHAR(43) NOT NULL UNIQUE
        CHECK (session_id ~ '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$'),
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100) CHECK (principal_name IS NULL OR
        principal_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
);
CREATE INDEX spring_session_expiry ON spring_session (expiry_time);
CREATE INDEX spring_session_principal ON spring_session (principal_name);
CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES spring_session (primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
