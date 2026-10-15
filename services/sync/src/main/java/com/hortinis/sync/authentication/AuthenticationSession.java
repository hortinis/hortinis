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

  /** Full authority requires completed factors within the current login lifetime. */
  public boolean hasCurrentFactors(Instant now) {
    return AuthenticationLifetime.fullIsCurrent(loginAt, bothFactorsAt, now);
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
