using TypeSpec.Http;

namespace Hortinis.Sync {
  model OperationResultResponse extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: Models.OperationResult;
  }

  @error
  model InvalidRequestResponse extends NonCacheableResponse {
    @statusCode statusCode: 400;
    @body body: Models.InvalidRequestError;
  }

  @error
  model RecordNotFoundResponse extends NonCacheableResponse {
    @statusCode statusCode: 404;
    @body body: Models.RecordNotFoundError;
  }

  @error
  model ConflictResponse extends NonCacheableResponse {
    @statusCode statusCode: 409;
    @body body: Models.ConflictError;
  }

  model ChangePageResponse extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: Models.ChangePage;
  }

  model ReconciliationSessionResponse extends NonCacheableResponse {
    @statusCode statusCode: 201;
    @body body: Models.ReconciliationSession;
  }

  model SnapshotPageResponse extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: Models.SnapshotPage;
  }

  @error
  model SnapshotExpiredResponse extends NonCacheableResponse {
    @statusCode statusCode: 410;
    @body body: Models.SnapshotExpiredError;
  }

  @route("/api/v1/sync")
  interface SynchronizationOperations {
    @post
    @route("/operations")
    @TypeSpec.OpenAPI.operationId("submitTechnicalRecordOperation")
    @summary("Submit one technical-record operation")
    @doc("Accept or identically replay one create, replacement, or deletion operation. Generation binding becomes mandatory in G3.")
    submitOperation(
      @body operation: Models.TechnicalRecordOperation,
      @header("Sync-Expectation") expectation?: Models.ExchangeExpectation,
    ):
      | OperationResultResponse
      | InvalidRequestResponse
      | RecordNotFoundResponse
      | ConflictResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | BindingPreconditionFailedResponse
      | AccessUnavailableResponse;

    @get
    @route("/changes")
    @TypeSpec.OpenAPI.operationId("pullTechnicalRecordChanges")
    @summary("Pull accepted technical-record changes")
    @doc("Read accepted changes after an optional opaque cursor.")
    pullChanges(
      @query cursor?: Models.SyncCursor,
      @header("Sync-Expectation") expectation?: Models.ExchangeExpectation,
    ):
      | ChangePageResponse
      | InvalidRequestResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | BindingPreconditionFailedResponse
      | AccessUnavailableResponse;

    @post
    @route("/reconciliations")
    @TypeSpec.OpenAPI.operationId("startTechnicalRecordReconciliation")
    @summary("Start one anchored full-reconciliation session")
    @doc("Capture an internally consistent technical-record snapshot at one server sequence.")
    startReconciliation(
      @body request: Models.StartReconciliationRequest,
      @header("Sync-Expectation") expectation?: Models.ExchangeExpectation,
    ):
      | ReconciliationSessionResponse
      | InvalidRequestResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | BindingPreconditionFailedResponse
      | AccessUnavailableResponse;

    @get
    @route("/reconciliations/{reconciliationId}/snapshot")
    @TypeSpec.OpenAPI.operationId("pullTechnicalRecordSnapshot")
    @summary("Pull one page from an anchored reconciliation snapshot")
    @doc("Continue an immutable snapshot session with its opaque, session-bound cursor.")
    pullSnapshot(
      @path reconciliationId: Models.ReconciliationId,
      @query cursor: Models.SnapshotCursor,
      @header("Sync-Expectation") expectation?: Models.ExchangeExpectation,
    ):
      | SnapshotPageResponse
      | InvalidRequestResponse
      | SnapshotExpiredResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | BindingPreconditionFailedResponse
      | AccessUnavailableResponse;
  }
}
