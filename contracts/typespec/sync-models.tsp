@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @doc("A canonical lowercase UUID identifier.")
  @format("uuid")
  @pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
  scalar Uuid extends string;

  @doc("A positive integer encoded as a decimal string for cross-runtime precision.")
  @pattern("^[1-9][0-9]*$")
  scalar PositiveDecimal extends string;

  @doc("An opaque, non-empty incremental synchronization cursor.")
  @minLength(1)
  scalar SyncCursor extends string;

  @doc("The deliberately technical record used to validate the synchronization mechanism.")
  model TechnicalRecord {
    recordId: Uuid;
    revision: PositiveDecimal;
    value: string;
  }

  @doc("Create a technical record that does not yet exist.")
  model CreateTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: Uuid;

    recordId: Uuid;
    value: string;
    kind: "create";
  }

  @doc("Replace a technical record at its expected server revision.")
  model ReplaceTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: Uuid;

    recordId: Uuid;
    value: string;
    kind: "replace";
    expectedRevision: PositiveDecimal;
  }

  @doc("Delete an existing technical record at its expected server revision.")
  model DeleteTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: Uuid;

    recordId: Uuid;
    kind: "delete";
    expectedRevision: PositiveDecimal;
  }

  @doc("One stable technical-record operation submitted for synchronization.")
  @discriminated(#{ envelope: "none", discriminatorPropertyName: "kind" })
  @TypeSpec.JsonSchema.oneOf
  union TechnicalRecordOperation {
    create: CreateTechnicalRecordOperation,
    replace: ReplaceTechnicalRecordOperation,
    delete: DeleteTechnicalRecordOperation,
  }

  @doc("The stable result returned when a create or replacement is accepted or replayed identically.")
  model RecordOperationResult {
    outcome: "accepted";
    operationId: Uuid;
    record: TechnicalRecord;
    sequence: PositiveDecimal;
  }

  @doc("The retained synchronization representation of an accepted deletion.")
  model TechnicalTombstone {
    recordId: Uuid;
    revision: PositiveDecimal;
    deletedAtSequence: PositiveDecimal;
  }

  @doc("The stable result returned when a deletion is accepted or replayed identically.")
  model TombstoneOperationResult {
    outcome: "accepted";
    operationId: Uuid;
    tombstone: TechnicalTombstone;
    sequence: PositiveDecimal;
  }

  @doc("The stable result returned when an operation is accepted or replayed identically.")
  @TypeSpec.JsonSchema.oneOf
  union OperationResult {
    record: RecordOperationResult,
    tombstone: TombstoneOperationResult,
  }

  @doc("One accepted live-record change in monotonic server-sequence order.")
  model RecordTechnicalChange {
    operationId: Uuid;
    record: TechnicalRecord;
    sequence: PositiveDecimal;
  }

  @doc("One accepted deletion in monotonic server-sequence order.")
  model TombstoneTechnicalChange {
    operationId: Uuid;
    tombstone: TechnicalTombstone;
    sequence: PositiveDecimal;
  }

  @doc("One accepted server change in monotonic server-sequence order.")
  @TypeSpec.JsonSchema.oneOf
  union TechnicalChange {
    record: RecordTechnicalChange,
    tombstone: TombstoneTechnicalChange,
  }

  @doc("A page of accepted changes and the opaque cursor following the complete page.")
  model ChangePage {
    changes: TechnicalChange[];
    nextCursor: SyncCursor;
    hasMore: boolean;
  }

  @doc("The request does not satisfy the synchronization contract.")
  model InvalidRequestError {
    code: "INVALID_REQUEST";

    @minLength(1)
    message: string;
  }

  @doc("The operation targets a technical record that does not exist.")
  model RecordNotFoundError {
    code: "RECORD_NOT_FOUND";

    @minLength(1)
    message: string;

    operationId: Uuid;
    recordId: Uuid;
  }

  @doc("The operation identifier was already used with a different canonical request.")
  model OperationIdReusedError {
    code: "OPERATION_ID_REUSED";

    @minLength(1)
    message: string;

    operationId: Uuid;
  }

  @doc("Creation failed because the technical record already exists.")
  model RecordAlreadyExistsError {
    code: "RECORD_ALREADY_EXISTS";

    @minLength(1)
    message: string;

    operationId: Uuid;
    currentRecord: TechnicalRecord;
  }

  @doc("Creation failed because the stable record identifier was retired by an accepted deletion.")
  model RecordIdentifierRetiredError {
    code: "RECORD_IDENTIFIER_RETIRED";

    @minLength(1)
    message: string;

    operationId: Uuid;
    recordId: Uuid;
  }

  @doc("Replacement failed because the expected and current revisions differ.")
  model RevisionConflictError {
    code: "REVISION_CONFLICT";

    @minLength(1)
    message: string;

    operationId: Uuid;
    expectedRevision: PositiveDecimal;
    currentRecord: TechnicalRecord;
  }

  @discriminated(#{ envelope: "none", discriminatorPropertyName: "code" })
  @TypeSpec.JsonSchema.oneOf
  union ConflictError {
    OPERATION_ID_REUSED: OperationIdReusedError,
    RECORD_ALREADY_EXISTS: RecordAlreadyExistsError,
    RECORD_IDENTIFIER_RETIRED: RecordIdentifierRetiredError,
    REVISION_CONFLICT: RevisionConflictError,
  }
}
