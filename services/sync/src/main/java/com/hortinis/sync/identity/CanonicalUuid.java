package com.hortinis.sync.identity;

import java.util.UUID;

/** Shared framework-independent canonical UUID syntax. */
public final class CanonicalUuid {

  private CanonicalUuid() {}

  /** Accepts canonical lowercase variant-two UUIDs without selecting a UUID version. */
  public static boolean isValid(String value) {
    if (value == null) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.variant() == 2 && parsed.toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
