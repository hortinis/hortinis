package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PasswordVerifierTest {
  @Test
  void usesTheAcceptedArgon2ParametersFreshSaltsAndExactPasswordText() {
    PasswordVerifier verifier = new PasswordVerifier(new CryptoWorkLimiter());
    String password = "synthetic 🌱 passphrase ";
    String first = verifier.encode(password);
    String second = verifier.encode(password);
    assertThat(first).startsWith("{argon2id}$argon2id$v=19$m=65536,t=3,p=1$").isNotEqualTo(second);
    String[] parts = first.split("\\$");
    assertThat(Base64.getDecoder().decode(parts[4])).hasSize(32);
    assertThat(Base64.getDecoder().decode(parts[5])).hasSize(32);
    assertThat(verifier.matches(password, first)).isTrue();
    assertThat(verifier.matches(password.trim(), first)).isFalse();
    assertThat(verifier.matches(password.toUpperCase(java.util.Locale.ROOT), first)).isFalse();
    assertThat(verifier.matches(password, second)).isTrue();
  }

  @Test
  void deniesUnknownSchemesUnsafeCostsAndMissingVerifiersWithoutParsingAttackerSelectedWork() {
    PasswordVerifier verifier = new PasswordVerifier(new CryptoWorkLimiter());
    String password = "synthetic verifier fixture";
    String selected = verifier.encode(password);
    for (String invalid :
        new String[] {
          null,
          "{noop}" + password,
          selected.replace("m=65536", "m=1048576"),
          selected + "$extra",
          selected.substring(0, selected.length() - 2),
          selected.replace("v=19", "v=16")
        }) {
      assertThat(verifier.matches(password, invalid)).isFalse();
    }
    assertThat(verifier.matches(null, selected)).isFalse();
    assertThat(verifier.matches("", selected)).isFalse();
    assertThat(verifier.matches("🌱".repeat(129), selected)).isFalse();
    assertThatThrownBy(() -> verifier.encode("\ud800"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
