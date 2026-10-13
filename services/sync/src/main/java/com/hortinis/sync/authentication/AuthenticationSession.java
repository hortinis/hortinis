package com.hortinis.sync.authentication;

import java.time.Instant;
import java.util.UUID;

/** Internal ceremony snapshot shared by publication, proof admission and physical cleanup. */
public record AuthenticationSession(
    UUID reference,
    UUID account,
    long epoch,
    String state,
    Instant createdAt,
    Instant expiresAt,
    Instant loginAt,
    Instant interactionAt,
    Instant bothFactorsAt,
    String stagedLogin,
    String stagedPassword,
    byte[] stagedHandle,
    String logoutTarget,
    String recoveryKind,
    byte[] replacementCredential) {
  /** Retains immutable snapshots of staged public handles and credential selectors. */
  public AuthenticationSession {
    if (replacementCredential != null) {
      replacementCredential = replacementCredential.clone();
    }
    if (stagedHandle != null) {
      stagedHandle = stagedHandle.clone();
    }
  }

  /** Enforces the ceremony's fixed creation and expiry bounds. */
  public boolean isUnexpired(Instant now) {
    return AuthenticationLifetime.isUnexpired(createdAt, expiresAt, now);
  }

  /** A step-up ceremony retains the preceding full login's absolute deadline. */
  public boolean hasCurrentLogin(Instant now) {
    return loginAt == null
        || !now.isBefore(loginAt)
            && now.isBefore(loginAt.plus(AuthenticationLifetime.FULL_ABSOLUTE));
  }

  /** Full authority requires both factors and the login's idle and absolute lifetimes. */
  public boolean hasCurrentFactors(Instant now) {
    return AuthenticationLifetime.fullIsCurrent(loginAt, interactionAt, bothFactorsAt, now);
  }

  @Override
  public byte[] replacementCredential() {
    return replacementCredential == null ? null : replacementCredential.clone();
  }

  @Override
  public byte[] stagedHandle() {
    return stagedHandle == null ? null : stagedHandle.clone();
  }

  @Override
  public String toString() {
    return "AuthenticationSession[redacted]";
  }
}
