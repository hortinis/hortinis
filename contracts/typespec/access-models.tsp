@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @doc("A browser-owned offline data-set identity; never server authorization.")
  scalar LocalDataSetId extends Uuid;

  @doc("A stable, non-secret server installation identity.")
  scalar ServerInstanceId extends Uuid;

  @doc("A server-owned synchronization partition identity; never a credential.")
  scalar SynchronizationScopeId extends Uuid;

  @doc("A server-owned account identity, distinct from its scope.")
  scalar AccountId extends Uuid;

  union ServerAccessMode {
    noAuth: "single-user-no-auth",
    singleAuth: "single-user-auth",
    multiAuth: "multi-user-auth",
  }

  @doc("A non-secret expectation checked after independent authorization; never carried in URLs.")
  @pattern("^[A-Za-z0-9_-]{1,256}$")
  scalar ExchangeExpectation extends string;

  @doc("ASCII origin syntax; international hostnames use normalized punycode. The binding adapter additionally validates the URL authority.")
  @pattern("^https?://[a-z0-9.\\[\\]:%-]+$")
  @maxLength(2048)
  scalar ServerOrigin extends string;

  model G2SynchronizationBoundary {
    kind: "g2";
  }

  model GenerationSynchronizationBoundary {
    kind: "generation";
    generation: SyncGeneration;
  }

  @TypeSpec.JsonSchema.oneOf
  union SynchronizationBoundary {
    g2: G2SynchronizationBoundary,
    generation: GenerationSynchronizationBoundary,
  }

  model ServerCapabilities {
    contractVersion: "1";
    serverInstanceId: ServerInstanceId;
    accessMode: ServerAccessMode;

    @minItems(1)
    @maxItems(2)
    @TypeSpec.JsonSchema.uniqueItems
    @TypeSpec.OpenAPI.extension("uniqueItems", true)
    supportedBoundaries: ("g2" | "generation")[];

    anchoredSnapshots: boolean;
  }

  model ConfiguredScopeIdentity {
    authority: "configured";
    accessMode: "single-user-no-auth";
    synchronizationScopeId: SynchronizationScopeId;
  }

  model AuthenticatedScopeIdentity {
    authority: "authenticated";
    accessMode: "single-user-auth" | "multi-user-auth";
    synchronizationScopeId: SynchronizationScopeId;
    accountId: AccountId;
  }

  @TypeSpec.JsonSchema.oneOf
  union AuthorizedScopeIdentity {
    configured: ConfiguredScopeIdentity,
    authenticated: AuthenticatedScopeIdentity,
  }

  model SynchronizationBootstrap {
    contractVersion: "1";
    serverInstanceId: ServerInstanceId;
    identity: AuthorizedScopeIdentity;
    population: "empty" | "populated";
    boundary: SynchronizationBoundary;
    expectation: ExchangeExpectation;
  }

  model ConfirmEmptyBindingRequest {
    expectation: ExchangeExpectation;
    expectedPopulation: "empty";
  }

  @doc("Versioned local-only provenance; never a request authorizing server access.")
  model LocalServerBinding {
    version: "1";
    localDataSetId: LocalDataSetId;
    serverOrigin: ServerOrigin;
    serverInstanceId: ServerInstanceId;
    identity: AuthorizedScopeIdentity;
    boundary: SynchronizationBoundary;
  }

  @doc("Local exchange readiness is separate from persisted binding and current server permission.")
  model SettledLocalExchangeState {
    state:
      | "offline"
      | "binding_pending"
      | "bound"
      | "auth_required"
      | "binding_mismatch"
      | "revoked"
      | "reconciliation_required"
      | "scope_deleted"
      | "disconnected";
    exchangeFence: PositiveDecimal;
    logoutPending: false;
  }

  model PendingLogoutExchangeState {
    state: "auth_required";
    exchangeFence: PositiveDecimal;
    logoutPending: true;
  }

  @TypeSpec.JsonSchema.oneOf
  union LocalExchangeState {
    settled: SettledLocalExchangeState,
    pendingLogout: PendingLogoutExchangeState,
  }

  model AuthenticationRequiredError {
    code: "AUTHENTICATION_REQUIRED";
    message: "Authentication is required.";
  }

  model AccessDeniedError {
    code: "ACCESS_DENIED";
    message: "Access is denied.";
  }

  model BindingPreconditionFailedError {
    code: "BINDING_PRECONDITION_FAILED";
    message: "The binding precondition no longer holds.";
  }

  model AccessUnavailableError {
    code: "ACCESS_UNAVAILABLE";
    message: "The access service is unavailable.";
  }

  @TypeSpec.JsonSchema.oneOf
  union AccessError {
    authentication: AuthenticationRequiredError,
    denied: AccessDeniedError,
    precondition: BindingPreconditionFailedError,
    unavailable: AccessUnavailableError,
  }
}
