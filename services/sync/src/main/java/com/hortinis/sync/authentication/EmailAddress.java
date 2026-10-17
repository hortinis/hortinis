package com.hortinis.sync.authentication;

import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded ASCII mailbox policy shared by wire guards and account lookup. */
public final class EmailAddress {
  private static final Pattern SYNTAX =
      Pattern.compile(
          "(?=.{1,64}@)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
              + "(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
              + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
              + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

  private EmailAddress() {}

  /** Validates exact submitted text before case folding; never trims or rewrites aliases. */
  public static String canonical(String value) {
    if (value == null || value.length() > 254 || !SYNTAX.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid email address.");
    }
    return value.toLowerCase(Locale.ROOT);
  }
}
