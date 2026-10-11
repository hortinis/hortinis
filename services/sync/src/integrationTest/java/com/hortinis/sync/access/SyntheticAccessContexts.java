package com.hortinis.sync.access;

/** Trusted synthetic contexts only; this helper is absent from production source sets. */
public final class SyntheticAccessContexts {
  private SyntheticAccessContexts() {}

  /** Creates authority from identities provisioned by controlled integration-test setup. */
  public static SynchronizationAccessContext configured(
      ServerInstanceId instance, SynchronizationScopeId scope) {
    return SynchronizationAccessContext.configured(instance, scope);
  }

  /** Constructs unsupported authenticated authority solely for fail-closed boundary tests. */
  public static SynchronizationAccessContext authenticated(
      ServerInstanceId instance,
      SynchronizationScopeId scope,
      ServerAccessMode mode,
      AccountId account) {
    return SynchronizationAccessContext.authenticated(instance, scope, mode, account);
  }
}
