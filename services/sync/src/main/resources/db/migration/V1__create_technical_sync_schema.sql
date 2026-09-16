CREATE TABLE technical_record (
    record_id UUID PRIMARY KEY,
    revision BIGINT NOT NULL CHECK (revision > 0),
    value TEXT NOT NULL
);

CREATE TABLE accepted_technical_record_operation (
    operation_id UUID PRIMARY KEY,
    operation_kind TEXT NOT NULL CHECK (operation_kind IN ('create', 'replace')),
    record_id UUID NOT NULL,
    operation_value TEXT NOT NULL,
    expected_revision BIGINT CHECK (expected_revision > 0),
    CHECK (
        (operation_kind = 'create' AND expected_revision IS NULL)
        OR
        (operation_kind = 'replace' AND expected_revision IS NOT NULL)
    )
);

CREATE TABLE technical_record_change (
    server_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE
        REFERENCES accepted_technical_record_operation (operation_id),
    record_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    value TEXT NOT NULL,
    UNIQUE (record_id, revision)
);
