package com.hortinis.sync.access;

import java.util.Objects;

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
      AccountId account) {
    return new SynchronizationAccessContext(
        instance, scope, new AuthenticatedAuthority(mode, account));
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

  /** An account and authenticated mode validated by the future access resolver. */
  public record AuthenticatedAuthority(ServerAccessMode mode, AccountId accountId)
      implements Authority {

    /** Prevents incomplete or no-auth authenticated contexts. */
    public AuthenticatedAuthority {
      Objects.requireNonNull(mode);
      Objects.requireNonNull(accountId);
      if (mode == ServerAccessMode.SINGLE_USER_NO_AUTH) {
        throw new IllegalArgumentException("The access authority is invalid.");
      }
    }
  }
}
