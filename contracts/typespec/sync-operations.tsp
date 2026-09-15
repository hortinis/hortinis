using TypeSpec.Http;

namespace Hortinis.Sync {
  model OperationResultResponse {
    @statusCode statusCode: 200;
    @body body: Models.OperationResult;
  }

  @error
  model InvalidRequestResponse {
    @statusCode statusCode: 400;
    @body body: Models.InvalidRequestError;
  }

  @error
  model RecordNotFoundResponse {
    @statusCode statusCode: 404;
    @body body: Models.RecordNotFoundError;
  }

  @error
  model ConflictResponse {
    @statusCode statusCode: 409;
    @body body: Models.ConflictError;
  }

  model ChangePageResponse {
    @statusCode statusCode: 200;
    @body body: Models.ChangePage;
  }

  @route("/api/v1/sync")
  interface SynchronizationOperations {
    @post
    @route("/operations")
    @TypeSpec.OpenAPI.operationId("submitTechnicalRecordOperation")
    @summary("Submit one technical-record operation")
    @doc("Accept or identically replay one technical-record operation.")
    submitOperation(@body operation: Models.TechnicalRecordOperation):
      | OperationResultResponse
      | InvalidRequestResponse
      | RecordNotFoundResponse
      | ConflictResponse;

    @get
    @route("/changes")
    @TypeSpec.OpenAPI.operationId("pullTechnicalRecordChanges")
    @summary("Pull accepted technical-record changes")
    @doc("Read accepted changes after an optional opaque cursor.")
    pullChanges(@query cursor?: Models.SyncCursor):
      | ChangePageResponse
      | InvalidRequestResponse;
  }
}
