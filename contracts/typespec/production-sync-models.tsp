@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @doc("An opaque, non-empty synchronization-generation identifier controlled by the server.")
  @minLength(1)
  scalar SyncGeneration extends string;

  @doc("An opaque, non-empty reconciliation-session identifier controlled by the server.")
  @minLength(1)
  scalar ReconciliationId extends string;

  @doc("An opaque, non-empty cursor bound to one anchored snapshot session.")
  @minLength(1)
  scalar SnapshotCursor extends string;

  @doc("Delete an existing technical record at its expected server revision.")
  model DeleteTechnicalRecordOperation {
    @doc("The stable operation and idempotency identifier.")
    operationId: Uuid;

    recordId: Uuid;
    kind: "delete";
    expectedRevision: PositiveDecimal;
  }

  @doc("A production operation supported after generation handling and deletion are implemented.")
  @discriminated(#{ envelope: "none", discriminatorPropertyName: "kind" })
  @TypeSpec.JsonSchema.oneOf
  union ProductionTechnicalRecordOperation {
    create: CreateTechnicalRecordOperation,
    replace: ReplaceTechnicalRecordOperation,
    delete: DeleteTechnicalRecordOperation,
  }

  @doc("A production operation bound immutably to the generation of its first submission.")
  model GenerationBoundOperation {
    generation: SyncGeneration;
    operation: ProductionTechnicalRecordOperation;
  }

  @doc("The retained synchronization representation of an accepted deletion.")
  model TechnicalTombstone {
    recordId: Uuid;
    revision: PositiveDecimal;
    deletedAtSequence: PositiveDecimal;
  }

  @doc("A live record in an anchored snapshot.")
  model LiveSnapshotEntry {
    kind: "record";
    record: TechnicalRecord;
  }

  @doc("A retained deletion in an anchored snapshot.")
  model TombstoneSnapshotEntry {
    kind: "tombstone";
    tombstone: TechnicalTombstone;
  }

  @doc("One entry in a complete anchored server snapshot.")
  @discriminated(#{ envelope: "none", discriminatorPropertyName: "kind" })
  @TypeSpec.JsonSchema.oneOf
  union SnapshotEntry {
    record: LiveSnapshotEntry,
    tombstone: TombstoneSnapshotEntry,
  }

  @doc("Start a full reconciliation without previously persisted synchronization state.")
  model StartReconciliationWithoutState {}

  @doc("Start a full reconciliation from one matching generation and cursor pair.")
  model StartReconciliationFromState {
    previousGeneration: SyncGeneration;
    previousCursor: SyncCursor;
  }

  @doc("Start a full reconciliation either without state or from one complete persisted boundary.")
  @TypeSpec.JsonSchema.oneOf
  union StartReconciliationRequest {
    withoutState: StartReconciliationWithoutState,
    fromState: StartReconciliationFromState,
  }

  @doc("An anchored reconciliation session and its first opaque snapshot cursor.")
  model ReconciliationSession {
    reconciliationId: ReconciliationId;
    generation: SyncGeneration;
    anchorSequence: PositiveDecimal;
    firstSnapshotCursor: SnapshotCursor;
    expiresAt: utcDateTime;
  }

  @doc("A non-final page from one immutable anchored snapshot.")
  model SnapshotContinuationPage {
    reconciliationId: ReconciliationId;
    generation: SyncGeneration;
    anchorSequence: PositiveDecimal;
    entries: SnapshotEntry[];
    hasMore: true;
    nextCursor: SnapshotCursor;
  }

  @doc("The final snapshot page and the opaque incremental cursor positioned after its anchor.")
  model SnapshotFinalPage {
    reconciliationId: ReconciliationId;
    generation: SyncGeneration;
    anchorSequence: PositiveDecimal;
    entries: SnapshotEntry[];
    hasMore: false;
    incrementalCursor: SyncCursor;
  }

  @doc("One page from an internally consistent anchored snapshot.")
  @TypeSpec.JsonSchema.oneOf
  union SnapshotPage {
    continuation: SnapshotContinuationPage,
    final: SnapshotFinalPage,
  }

  @doc("Why incremental synchronization cannot continue.")
  union ReconciliationRequiredReason {
    generationExpired: "generation_expired",
    historyCompacted: "history_compacted",
    cursorExpired: "cursor_expired",
  }

  @doc("Incremental synchronization cannot continue and a full reconciliation is required.")
  model ReconciliationRequiredError {
    code: "RECONCILIATION_REQUIRED";

    @minLength(1)
    message: string;

    currentGeneration: SyncGeneration;
    reason: ReconciliationRequiredReason;
  }

  @doc("The anchored snapshot session or cursor has expired.")
  model SnapshotExpiredError {
    code: "SNAPSHOT_EXPIRED";

    @minLength(1)
    message: string;

    reconciliationId: ReconciliationId;
  }

  @doc("The server can no longer prove whether an operation was accepted.")
  model IndeterminateOperationOutcome {
    outcome: "indeterminate";
    operationId: Uuid;
    submittedGeneration: SyncGeneration;
    currentGeneration: SyncGeneration;
    reason: "receipt_unavailable";
  }

  @doc("Use anchored server state because no pending local intent overlaps this record.")
  model AdoptServerReconciliationOutcome {
    outcome: "adopt_server";
    recordId: Uuid;
  }

  @doc("Preserve a pending causal chain for explicit rebasing into the active generation.")
  model PreservePendingReconciliationOutcome {
    outcome: "preserve_pending";
    recordId: Uuid;
    operationIds: Uuid[];
  }

  @doc("Keep competing server state and local intent as an explicit record-level conflict.")
  model ConflictReconciliationOutcome {
    outcome: "conflict";
    recordId: Uuid;
    operationIds: Uuid[];
  }

  @doc("Keep an operation whose server acceptance remains unprovable as indeterminate local work.")
  model KeepIndeterminateReconciliationOutcome {
    outcome: "keep_indeterminate";
    recordId: Uuid;
    operationId: Uuid;
  }

  @doc("A domain-neutral per-record result of three-way full reconciliation.")
  @discriminated(#{ envelope: "none", discriminatorPropertyName: "outcome" })
  @TypeSpec.JsonSchema.oneOf
  union ReconciliationOutcome {
    adopt_server: AdoptServerReconciliationOutcome,
    preserve_pending: PreservePendingReconciliationOutcome,
    conflict: ConflictReconciliationOutcome,
    keep_indeterminate: KeepIndeterminateReconciliationOutcome,
  }
}
