package com.hortinis.sync.authentication;

import com.password4j.Argon2Function;
import com.password4j.SaltGenerator;
import com.password4j.types.Argon2;
import java.util.Base64;
import java.util.Map;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;

/**
 * Established verifier adapter with explicit cost and bounded work, never logged or sent on wire.
 */
public final class PasswordVerifier {
  private static final int SALT_BYTES = 32;
  private static final int VERIFIER_COMPONENTS = 2;
  private static final String PREFIX = "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$";
  private final CryptoWorkLimiter workLimiter;
  private final PasswordEncoder encoder;
  private final String missingAccountVerifier;

  /** Selects one explicit framework encoder and computes a private independent dummy verifier. */
  public PasswordVerifier(CryptoWorkLimiter workLimiter) {
    this.workLimiter = workLimiter;
    if (SaltGenerator.get() != SALT_BYTES) {
      throw new IllegalStateException("The explicit secure password salt policy is required.");
    }
    PasswordEncoder argon2 =
        new Argon2Password4jPasswordEncoder(
            Argon2Function.getInstance(65536, 3, 1, 32, Argon2.ID, 19));
    encoder = new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2));
    missingAccountVerifier = encode(AuthenticationSecrets.randomValue());
  }

  /**
   * Call only after new-password policy validation; this method still enforces safe text bounds.
   */
  public String encode(String password) {
    if (!PasswordPolicy.isValidLoginText(password)) {
      throw new IllegalArgumentException("Invalid password text.");
    }
    return workLimiter.execute(() -> encoder.encode(password));
  }

  /**
   * Absent/invalid stored state does equivalent-cost dummy work, then returns a generic failure.
   */
  public boolean matches(String password, String storedVerifier) {
    boolean validInput = PasswordPolicy.isValidLoginText(password);
    boolean validVerifier = isSelectedVerifier(storedVerifier);
    String candidate = validInput ? password : "invalid-input";
    String verifier = validVerifier ? storedVerifier : missingAccountVerifier;
    boolean matched = workLimiter.execute(() -> encoder.matches(candidate, verifier));
    return validInput && validVerifier && matched;
  }

  private static boolean isSelectedVerifier(String verifier) {
    if (verifier == null || !verifier.startsWith(PREFIX)) {
      return false;
    }
    String[] components = verifier.substring(PREFIX.length()).split("\\$", -1);
    if (components.length != VERIFIER_COMPONENTS) {
      return false;
    }
    try {
      Base64.Decoder decoder = Base64.getDecoder();
      byte[] salt = decoder.decode(components[0]);
      byte[] hash = decoder.decode(components[1]);
      Base64.Encoder canonical = Base64.getEncoder().withoutPadding();
      return salt.length == SALT_BYTES
          && hash.length == 32
          && components[0].equals(canonical.encodeToString(salt))
          && components[1].equals(canonical.encodeToString(hash));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
