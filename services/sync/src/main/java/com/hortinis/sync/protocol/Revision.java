package com.hortinis.sync.protocol;

/** A positive accepted revision with exact bounded advancement. */
public record Revision(long value) {

  /** Rejects zero and negative accepted revisions. */
  public Revision {
    if (value <= 0) {
      throw new IllegalArgumentException("The revision is invalid.");
    }
  }

  /** Parses a canonical accepted revision at a boundary. */
  public static Revision parse(String value) {
    if (!UuidRules.isPositiveDecimal(value)) {
      throw new IllegalArgumentException("The revision is invalid.");
    }
    try {
      return new Revision(Long.parseLong(value));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("The revision is invalid.");
    }
  }

  /** Advances exactly, failing instead of wrapping at exhaustion. */
  public Revision next() {
    return new Revision(Math.addExact(value, 1));
  }

  /** Returns the public decimal string without loss of precision. */
  public String toWire() {
    return Long.toString(value);
  }
}
