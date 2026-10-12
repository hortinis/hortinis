package com.hortinis.sync.authentication;

import java.util.Objects;
import java.util.UUID;

/**
 * Non-authorizing session-row reference; current persistent policy must still approve every access.
 */
public record SessionAuthorityReference(UUID value) {
  /** Rejects missing references without retaining an arbitrary principal or credential. */
  public SessionAuthorityReference {
    Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return "SessionAuthorityReference[redacted]";
  }
}
