ALTER TABLE accepted_technical_record_operation
    DROP CONSTRAINT accepted_technical_record_operation_operation_kind_check,
    DROP CONSTRAINT accepted_technical_record_operation_check;

ALTER TABLE accepted_technical_record_operation
    ALTER COLUMN operation_value DROP NOT NULL,
    ADD CONSTRAINT accepted_technical_record_operation_kind_check
        CHECK (operation_kind IN ('create', 'replace', 'delete')),
    ADD CONSTRAINT accepted_technical_record_operation_shape_check CHECK (
        (operation_kind = 'create' AND operation_value IS NOT NULL AND expected_revision IS NULL)
        OR
        (operation_kind = 'replace' AND operation_value IS NOT NULL AND expected_revision IS NOT NULL)
        OR
        (operation_kind = 'delete' AND operation_value IS NULL AND expected_revision IS NOT NULL)
    );

ALTER TABLE technical_record_change
    ADD COLUMN change_kind TEXT NOT NULL DEFAULT 'record',
    ALTER COLUMN value DROP NOT NULL,
    ADD CONSTRAINT technical_record_change_kind_check
        CHECK (change_kind IN ('record', 'tombstone')),
    ADD CONSTRAINT technical_record_change_shape_check CHECK (
        (change_kind = 'record' AND value IS NOT NULL)
        OR
        (change_kind = 'tombstone' AND value IS NULL)
    );

CREATE TABLE technical_record_tombstone (
    record_id UUID PRIMARY KEY,
    revision BIGINT NOT NULL CHECK (revision > 0),
    deleted_at_sequence BIGINT NOT NULL UNIQUE
);

CREATE TABLE retired_technical_record_identifier (
    record_id UUID PRIMARY KEY,
    retired_at_sequence BIGINT NOT NULL UNIQUE
);
