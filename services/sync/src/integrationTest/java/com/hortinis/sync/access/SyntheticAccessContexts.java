package com.hortinis.sync.access;

/** Trusted synthetic contexts only; this helper is absent from production source sets. */
public final class SyntheticAccessContexts {
  private SyntheticAccessContexts() {}

  /** Creates authority from identities provisioned by controlled integration-test setup. */
  public static SynchronizationAccessContext configured(
      ServerInstanceId instance, SynchronizationScopeId scope) {
    return SynchronizationAccessContext.configured(instance, scope);
  }

  /** Constructs unbacked authenticated metadata solely for fail-closed boundary tests. */
  public static SynchronizationAccessContext authenticated(
      ServerInstanceId instance,
      SynchronizationScopeId scope,
      ServerAccessMode mode,
      AccountId account) {
    return SynchronizationAccessContext.authenticated(
        instance, scope, mode, account, java.util.UUID.randomUUID(), 1);
  }

  /**
   * Uses current synthetic session rows for isolated transaction-boundary tests, never HTTP input.
   */
  public static SynchronizationAccessContext authenticatedSession(
      ServerInstanceId instance,
      SynchronizationScopeId scope,
      ServerAccessMode mode,
      AccountId account,
      java.util.UUID sessionReference,
      long epoch) {
    return SynchronizationAccessContext.authenticated(
        instance, scope, mode, account, sessionReference, epoch);
  }
}
