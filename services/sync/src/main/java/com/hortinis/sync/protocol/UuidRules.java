package com.hortinis.sync.protocol;

import com.hortinis.sync.identity.CanonicalUuid;
import java.util.regex.Pattern;

public final class UuidRules {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private UuidRules() {}

  public static boolean isCanonicalUuid(String value) {
    return CanonicalUuid.isValid(value);
  }

  public static boolean isPositiveDecimal(String value) {
    return value != null && POSITIVE_DECIMAL.matcher(value).matches();
  }
}
