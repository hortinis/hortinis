package com.hortinis.sync.protocol;

import java.util.UUID;
import java.util.regex.Pattern;

public final class UuidRules {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private UuidRules() {}

  public static boolean isCanonicalUuid(String value) {
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

  public static boolean isPositiveDecimal(String value) {
    return value != null && POSITIVE_DECIMAL.matcher(value).matches();
  }
}
