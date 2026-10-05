package com.hortinis.sync.protocol;

/** Shared value rules for the technical synchronization boundary. */
public final class TechnicalRecordValueRules {

  public static final int MAX_CODE_POINTS = 4096;

  private TechnicalRecordValueRules() {}

  /** Checks decoded code-point length and rejects characters that cannot round-trip unchanged. */
  public static boolean isValid(String value) {
    if (value == null || value.length() > MAX_CODE_POINTS * 2) {
      return false;
    }
    int count = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      count++;
      if (codePoint == 0
          || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
          || count > MAX_CODE_POINTS) {
        return false;
      }
      offset += Character.charCount(codePoint);
    }
    return true;
  }
}
