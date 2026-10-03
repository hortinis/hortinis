using TypeSpec.Http;

namespace Hortinis.Sync {
  model NonCacheableResponse {
    @header("Cache-Control") cacheControl: "no-store";
  }

  @error
  model AuthenticationRequiredResponse extends NonCacheableResponse {
    @statusCode statusCode: 401;
    @body body: Models.AuthenticationRequiredError;
  }

  @error
  model AccessDeniedResponse extends NonCacheableResponse {
    @statusCode statusCode: 403;
    @body body: Models.AccessDeniedError;
  }

  @error
  model BindingPreconditionFailedResponse extends NonCacheableResponse {
    @statusCode statusCode: 409;
    @body body: Models.BindingPreconditionFailedError;
  }

  @error
  model AccessUnavailableResponse extends NonCacheableResponse {
    @statusCode statusCode: 503;
    @body body: Models.AccessUnavailableError;
  }

  model ServerCapabilitiesResponse extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: Models.ServerCapabilities;
  }

  model SynchronizationBootstrapResponse extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: Models.SynchronizationBootstrap;
  }

  @route("/api/v1/server")
  interface ServerDiscoveryOperations {
    @get
    @route("/capabilities")
    @TypeSpec.OpenAPI.operationId("readServerCapabilities")
    @summary("Read public server compatibility information")
    @doc("Contract-only until S6. Never return account/scope identity, content, or setup secrets.")
    readCapabilities(): ServerCapabilitiesResponse | AccessUnavailableResponse;
  }

  @route("/api/v1/sync")
  interface BindingOperations {
    @get
    @route("/bootstrap")
    @TypeSpec.OpenAPI.operationId("readSynchronizationBootstrap")
    @summary("Observe the currently authorized synchronization destination")
    @doc("Contract-only until S6. Resolve current access before reading population or identity. This observation does not authorize later exchange or advance a cursor.")
    readBootstrap():
      | SynchronizationBootstrapResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | AccessUnavailableResponse;

    @post
    @route("/bindings/confirm")
    @TypeSpec.OpenAPI.operationId("confirmEmptySynchronizationBinding")
    @summary("Confirm an observed empty synchronization destination")
    @doc("Contract-only until S6. Revalidate current access, expectation, and empty population atomically. Never provision a scope from this request.")
    confirmBinding(@body request: Models.ConfirmEmptyBindingRequest):
      | SynchronizationBootstrapResponse
      | InvalidRequestResponse
      | AuthenticationRequiredResponse
      | AccessDeniedResponse
      | BindingPreconditionFailedResponse
      | AccessUnavailableResponse;
  }
}
