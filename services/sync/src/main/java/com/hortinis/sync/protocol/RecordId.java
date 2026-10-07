package com.hortinis.sync.protocol;

import com.hortinis.sync.identity.CanonicalUuid;

/** A canonical technical record identifier, never included in diagnostics. */
public record RecordId(String value) {

  /** Validates canonical lowercase variant-two UUID syntax without restricting version. */
  public RecordId {
    if (!CanonicalUuid.isValid(value)) {
      throw new IllegalArgumentException("The record identifier is invalid.");
    }
  }

  /** Parses a public identifier without normalization. */
  public static RecordId parse(String value) {
    return new RecordId(value);
  }

  /** Returns the canonical public representation. */
  public String toWire() {
    return value;
  }

  @Override
  public String toString() {
    return "RecordId[redacted]";
  }
}
