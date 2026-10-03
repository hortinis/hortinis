package com.hortinis.sync.access;

import com.hortinis.sync.identity.CanonicalUuid;

/** A distinct non-secret identity, never proof of authorization. */
public record AccountId(String value) {

  /** Validates the canonical UUID syntax without restricting UUID version. */
  public AccountId {
    if (!CanonicalUuid.isValid(value)) {
      throw new IllegalArgumentException("The identity is invalid.");
    }
  }

  @Override
  public String toString() {
    return "AccountId[redacted]";
  }
}
