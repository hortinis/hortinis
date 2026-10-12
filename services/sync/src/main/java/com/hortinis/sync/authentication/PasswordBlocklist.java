package com.hortinis.sync.authentication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Bundled offline reference data; corruption or missing coverage fails closed before activation.
 */
public final class PasswordBlocklist {
  private static final String ARTIFACT_HASH =
      "2d136a42a3d7d9d4165dec0cf0fabc3e84cee4865ba290a50c9cfcec4b980870";
  private static final int ARTIFACT_BYTES = 650000;
  private static final int ENTRY_COUNT = 10000;

  private PasswordBlocklist() {}

  /** Verifies the complete bundled artifact before returning any reference entry. */
  public static Set<String> load() {
    try (InputStream input =
        PasswordBlocklist.class.getResourceAsStream("/authentication/password-blocklist.sha256")) {
      return readVerified(input);
    } catch (IOException exception) {
      throw unavailable();
    }
  }

  static Set<String> readVerified(InputStream input) throws IOException {
    if (input == null) {
      throw unavailable();
    }
    byte[] bytes = input.readNBytes(ARTIFACT_BYTES + 1);
    if (bytes.length != ARTIFACT_BYTES
        || !AuthenticationSecrets.digestBytes(bytes).equals(ARTIFACT_HASH)) {
      throw unavailable();
    }
    String[] entries = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
    if (entries.length != ENTRY_COUNT + 1 || !entries[ENTRY_COUNT].isEmpty()) {
      throw unavailable();
    }
    Set<String> result = new HashSet<>();
    String previous = "";
    for (String entry : Arrays.copyOf(entries, ENTRY_COUNT)) {
      if (!entry.matches("[a-f0-9]{64}") || previous.compareTo(entry) >= 0 || !result.add(entry)) {
        throw unavailable();
      }
      previous = entry;
    }
    return Set.copyOf(result);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Required offline password reference data is unavailable.");
  }
}
