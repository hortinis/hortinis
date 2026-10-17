package com.hortinis.sync.authentication;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-text password bounds and local reference/context rejection, independent of HTTP and
 * storage.
 */
public final class PasswordPolicy {
  private final Set<String> blockedDigests;

  /** Copies reviewed reference digests; neither inputs nor matches become diagnostics. */
  public PasswordPolicy(Set<String> blockedDigests) {
    this.blockedDigests = Set.copyOf(Objects.requireNonNull(blockedDigests));
  }

  /** Login deliberately permits old short passwords without applying new-password corpus policy. */
  public static boolean isValidLoginText(String value) {
    return isScalarText(value, 1, 128, 1024);
  }

  /** Applies new-password bounds and exact local digest/context checks. */
  public boolean acceptsNewPassword(String password, String canonicalLogin) {
    if (!isScalarText(password, 15, 128, 1024) || !isCanonicalLogin(canonicalLogin)) {
      return false;
    }
    String context = contextText(password);
    String loginContext = contextText(canonicalLogin.substring(0, canonicalLogin.indexOf('@')));
    return !blockedDigests.contains(AuthenticationSecrets.digest(password))
        && !context.contains("hortinis")
        && !isLoginDerivative(context, loginContext);
  }

  /** Canonicalization is for email identity only; never apply it to passwords. */
  public static String canonicalLogin(String value) {
    return EmailAddress.canonical(value);
  }

  private static boolean isCanonicalLogin(String value) {
    try {
      return EmailAddress.canonical(value).equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isLoginDerivative(String context, String loginContext) {
    // A punctuation-heavy identifier may compact to one or two characters. In that case reject
    // whole context/digit derivatives, not an unrelated strong phrase containing a common letter.
    return loginContext.length() >= 3
        ? context.contains(loginContext)
        : context.matches("[0-9]*" + java.util.regex.Pattern.quote(loginContext) + "[0-9]*");
  }

  private static String contextText(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  static boolean isScalarText(String value, int minimum, int maximum, int byteMaximum) {
    if (value == null) {
      return false;
    }
    int scalars = 0;
    for (int offset = 0; offset < value.length(); offset++) {
      char character = value.charAt(offset);
      if (Character.isHighSurrogate(character)) {
        offset++;
        if (offset == value.length() || !Character.isLowSurrogate(value.charAt(offset))) {
          return false;
        }
      } else if (Character.isLowSurrogate(character)) {
        return false;
      }
      scalars++;
      if (scalars > maximum) {
        return false;
      }
    }
    return scalars >= minimum && value.getBytes(StandardCharsets.UTF_8).length <= byteMaximum;
  }
}
