package com.hortinis.sync.protocol;

import com.hortinis.sync.identity.CanonicalUuid;

/** A canonical technical operation identifier, never included in diagnostics. */
public record OperationId(String value) {

  /** Validates canonical lowercase variant-two UUID syntax without restricting version. */
  public OperationId {
    if (!CanonicalUuid.isValid(value)) {
      throw new IllegalArgumentException("The operation identifier is invalid.");
    }
  }

  /** Parses a public identifier without normalization. */
  public static OperationId parse(String value) {
    return new OperationId(value);
  }

  /** Returns the canonical public representation. */
  public String toWire() {
    return value;
  }

  @Override
  public String toString() {
    return "OperationId[redacted]";
  }
}
