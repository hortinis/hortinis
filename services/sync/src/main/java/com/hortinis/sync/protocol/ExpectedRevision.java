package com.hortinis.sync.protocol;

import java.math.BigInteger;

/** An unbounded positive request revision, distinct from bounded accepted revisions. */
public record ExpectedRevision(BigInteger value) {

  /** Rejects missing, zero, and negative expectations. */
  public ExpectedRevision {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException("The expected revision is invalid.");
    }
  }

  /** Parses canonical decimal syntax without narrowing the public request contract. */
  public static ExpectedRevision parse(String value) {
    if (!UuidRules.isPositiveDecimal(value)) {
      throw new IllegalArgumentException("The expected revision is invalid.");
    }
    return new ExpectedRevision(new BigInteger(value));
  }

  /** Compares an expectation with a server-accepted revision without truncation. */
  public boolean matches(Revision revision) {
    return value.equals(BigInteger.valueOf(revision.value()));
  }

  /** Returns the canonical public decimal representation. */
  public String toWire() {
    return value.toString();
  }
}
