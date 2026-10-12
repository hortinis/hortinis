package com.hortinis.sync.authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Independent random secrets and JDK primitives; no stable identifier is used as secret material.
 */
public final class AuthenticationSecrets {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private AuthenticationSecrets() {}

  /** Generates independent CSPRNG bytes with canonical unpadded base64url encoding. */
  public static String randomValue() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** Validates unused base64 bits as well as size/alphabet to prevent alternate encodings. */
  public static boolean isRandomValue(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
  }

  /** Computes the exact UTF-8 SHA-256 reference/secret verifier. */
  public static String digest(String value) {
    return digestBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  static String digestBytes(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Required authentication digest is unavailable.");
    }
  }

  /** Compares fixed-size valid secret verifiers using the JDK constant-time primitive. */
  public static boolean matchesDigest(String value, String expectedHex) {
    if (!isRandomValue(value) || expectedHex == null || !expectedHex.matches("[a-f0-9]{64}")) {
      return false;
    }
    return MessageDigest.isEqual(
        HexFormat.of().parseHex(digest(value)), HexFormat.of().parseHex(expectedHex));
  }
}
