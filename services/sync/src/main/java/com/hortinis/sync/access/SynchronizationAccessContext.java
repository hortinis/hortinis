package com.hortinis.sync.access;

import java.util.Objects;
import java.util.UUID;

/** Internal trusted authority. Wire parsers cannot construct this context. */
public final class SynchronizationAccessContext {

  private final ServerInstanceId resolvedInstance;
  private final SynchronizationScopeId resolvedScope;
  private final Authority resolvedAuthority;

  private SynchronizationAccessContext(
      ServerInstanceId serverInstanceId,
      SynchronizationScopeId synchronizationScopeId,
      Authority authority) {
    this.resolvedInstance = Objects.requireNonNull(serverInstanceId);
    this.resolvedScope = Objects.requireNonNull(synchronizationScopeId);
    this.resolvedAuthority = Objects.requireNonNull(authority);
  }

  static SynchronizationAccessContext configured(
      ServerInstanceId instance, SynchronizationScopeId scope) {
    return new SynchronizationAccessContext(instance, scope, new ConfiguredAuthority());
  }

  static SynchronizationAccessContext authenticated(
      ServerInstanceId instance,
      SynchronizationScopeId scope,
      ServerAccessMode mode,
      AccountId account,
      UUID sessionReference,
      long authorityEpoch) {
    return new SynchronizationAccessContext(
        instance,
        scope,
        new AuthenticatedAuthority(mode, account, sessionReference, authorityEpoch));
  }

  /** Returns the installation selected by trusted resolution. */
  public ServerInstanceId serverInstanceId() {
    return resolvedInstance;
  }

  /** Returns the scope selected by trusted resolution. */
  public SynchronizationScopeId synchronizationScopeId() {
    return resolvedScope;
  }

  /** Returns the validated authority; never serialize it as an HTTP body. */
  public Authority authority() {
    return resolvedAuthority;
  }

  @Override
  public String toString() {
    return "SynchronizationAccessContext[redacted]";
  }

  /** Closed authority variants; no browser-declared device authority exists. */
  public sealed interface Authority permits ConfiguredAuthority, AuthenticatedAuthority {}

  /** Explicit no-auth configuration is not individual user isolation. */
  public record ConfiguredAuthority() implements Authority {}

  /** Internal account/session observation, rechecked in every protected persistence transaction. */
  public record AuthenticatedAuthority(
      ServerAccessMode mode, AccountId accountId, UUID sessionReference, long authorityEpoch)
      implements Authority {

    /** Prevents incomplete or no-auth authenticated contexts. */
    public AuthenticatedAuthority {
      Objects.requireNonNull(mode);
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(sessionReference);
      if (mode == ServerAccessMode.SINGLE_USER_NO_AUTH || authorityEpoch <= 0) {
        throw new IllegalArgumentException("The access authority is invalid.");
      }
    }

    @Override
    public String toString() {
      return "AuthenticatedAuthority[redacted]";
    }
  }
}
