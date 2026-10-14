package com.hortinis.sync.authentication;

/** Minimal immutable public verification state; contains no authenticator/attestation metadata. */
public record AuthenticationCredential(
    byte[] id,
    byte[] publicKey,
    int algorithm,
    long counter,
    boolean backupEligible,
    boolean backedUp,
    String label) {
  /** Copies all key material. */
  public AuthenticationCredential {
    id = id.clone();
    publicKey = publicKey.clone();
  }

  @Override
  public byte[] id() {
    return id.clone();
  }

  @Override
  public byte[] publicKey() {
    return publicKey.clone();
  }

  @Override
  public String toString() {
    return "AuthenticationCredential[redacted]";
  }
}
