package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AuthenticationSecretsTest {
  @Test
  void generatesIndependentCanonicalThirtyTwoByteValuesAndComparesOnlyTheirVerifiers() {
    var generated = new HashSet<String>();
    for (int index = 0; index < 100; index++) {
      String value = AuthenticationSecrets.randomValue();
      assertThat(AuthenticationSecrets.isRandomValue(value)).isTrue();
      assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
      assertThat(generated.add(value)).isTrue();
      assertThat(AuthenticationSecrets.matchesDigest(value, AuthenticationSecrets.digest(value)))
          .isTrue();
      assertThat(
              AuthenticationSecrets.matchesDigest(
                  value, AuthenticationSecrets.digest(AuthenticationSecrets.randomValue())))
          .isFalse();
      assertThat(
              AuthenticationSecrets.matchesDigest(value + "=", AuthenticationSecrets.digest(value)))
          .isFalse();
    }
  }

  @Test
  void deniesAliasBitsWrongLengthAndMalformedVerifiers() {
    String synthetic = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    assertThat(AuthenticationSecrets.isRandomValue(synthetic.substring(0, 42) + "B")).isFalse();
    assertThat(AuthenticationSecrets.isRandomValue(synthetic.substring(0, 42))).isFalse();
    assertThat(AuthenticationSecrets.isRandomValue(null)).isFalse();
    assertThat(AuthenticationSecrets.matchesDigest(synthetic, "invalid")).isFalse();
    assertThat(AuthenticationSecrets.matchesDigest(null, AuthenticationSecrets.digest(synthetic)))
        .isFalse();
  }
}
