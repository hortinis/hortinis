@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @doc("A canonical lowercase UUID version 7 identifier.")
  @format("uuid")
  @pattern("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
  scalar UuidV7 extends string;

  @doc("A positive integer encoded as a decimal string for cross-runtime precision.")
  @pattern("^[1-9][0-9]*$")
  scalar PositiveDecimal extends string;

  @doc("An opaque, non-empty incremental synchronization cursor.")
  @minLength(1)
  scalar SyncCursor extends string;

  @doc("The deliberately technical record used to validate the synchronization mechanism.")
  model TechnicalRecord {
    recordId: UuidV7;
    revision: PositiveDecimal;
    value: string;
  }

  @doc("Create a technical record that does not yet exist.")
  model CreateTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: UuidV7;

    recordId: UuidV7;
    value: string;
    kind: "create";
  }

  @doc("Replace a technical record at its expected server revision.")
  model ReplaceTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: UuidV7;

    recordId: UuidV7;
    value: string;
    kind: "replace";
    expectedRevision: PositiveDecimal;
  }

  @doc("One stable technical-record operation submitted for synchronization.")
  @discriminated(#{ envelope: "none", discriminatorPropertyName: "kind" })
  @TypeSpec.JsonSchema.oneOf
  union TechnicalRecordOperation {
    create: CreateTechnicalRecordOperation,
    replace: ReplaceTechnicalRecordOperation,
  }

  @doc("The stable result returned when an operation is accepted or replayed identically.")
  model OperationResult {
    outcome: "accepted";
    operationId: UuidV7;
    record: TechnicalRecord;
    sequence: PositiveDecimal;
  }

  @doc("One accepted server change in monotonic server-sequence order.")
  model TechnicalChange {
    operationId: UuidV7;
    record: TechnicalRecord;
    sequence: PositiveDecimal;
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

    operationId: UuidV7;
    recordId: UuidV7;
  }

  @doc("The operation identifier was already used with a different canonical request.")
  model OperationIdReusedError {
    code: "OPERATION_ID_REUSED";

    @minLength(1)
    message: string;

    operationId: UuidV7;
  }

  @doc("Creation failed because the technical record already exists.")
  model RecordAlreadyExistsError {
    code: "RECORD_ALREADY_EXISTS";

    @minLength(1)
    message: string;

    operationId: UuidV7;
    currentRecord: TechnicalRecord;
  }

  @doc("Replacement failed because the expected and current revisions differ.")
  model RevisionConflictError {
    code: "REVISION_CONFLICT";

    @minLength(1)
    message: string;

    operationId: UuidV7;
    expectedRevision: PositiveDecimal;
    currentRecord: TechnicalRecord;
  }

  @discriminated(#{ envelope: "none", discriminatorPropertyName: "code" })
  @TypeSpec.JsonSchema.oneOf
  union ConflictError {
    OPERATION_ID_REUSED: OperationIdReusedError,
    RECORD_ALREADY_EXISTS: RecordAlreadyExistsError,
    REVISION_CONFLICT: RevisionConflictError,
  }
}
