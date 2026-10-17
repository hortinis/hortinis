package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
  private static final String GARDENER = "gardener@example.test";

  private final PasswordPolicy policy = new PasswordPolicy(Set.of());

  @Test
  void enforcesScalarBoundsAndRejectsMalformedUnicodeWithoutCompositionRules() {
    assertThat(policy.acceptsNewPassword("🌱".repeat(15), GARDENER)).isTrue();
    assertThat(policy.acceptsNewPassword(" ".repeat(15), GARDENER)).isTrue();
    assertThat(policy.acceptsNewPassword("x".repeat(128), GARDENER)).isTrue();
    for (String rejected :
        new String[] {
          null,
          "x".repeat(14),
          "🌱".repeat(129),
          "x".repeat(15) + Character.MIN_HIGH_SURROGATE,
          "x".repeat(15) + Character.MIN_LOW_SURROGATE
        }) {
      assertThat(policy.acceptsNewPassword(rejected, GARDENER)).isFalse();
    }
    assertThat(PasswordPolicy.isValidLoginText("short")).isTrue();
    assertThat(PasswordPolicy.isValidLoginText("")).isFalse();
  }

  @Test
  void comparesExactReferenceTextWithoutTrimmingOrNormalizingIt() {
    String synthetic = "synthetic-é-phrase";
    PasswordPolicy blocked = new PasswordPolicy(Set.of(AuthenticationSecrets.digest(synthetic)));
    assertThat(blocked.acceptsNewPassword(synthetic, GARDENER)).isFalse();
    assertThat(blocked.acceptsNewPassword(" " + synthetic, GARDENER)).isTrue();
    assertThat(blocked.acceptsNewPassword("synthetic-é-phrase", GARDENER)).isTrue();
    assertThat(blocked.acceptsNewPassword(synthetic.toUpperCase(java.util.Locale.ROOT), GARDENER))
        .isTrue();
  }

  @Test
  void rejectsContextDerivativesAndCanonicalizesOnlyTheIdentifier() {
    assertThat(PasswordPolicy.canonicalLogin("A.Gardener-2@Example.test"))
        .isEqualTo("a.gardener-2@example.test");
    assertThat(policy.acceptsNewPassword("99-H-O-R-T-I-N-I-S-99", GARDENER)).isFalse();
    assertThat(policy.acceptsNewPassword("99-G-A-R-D-E-N-E-R-99", GARDENER)).isFalse();
    assertThat(policy.acceptsNewPassword("other long phrase!", "Gardener@example.test")).isFalse();
    for (String login : new String[] {" a.b", "a.b ", "ab", "éclair", "_invalid", "x".repeat(65)}) {
      assertThatThrownBy(() -> PasswordPolicy.canonicalLogin(login))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid email address.");
    }
  }

  @Test
  void shortCompactedIdentifiersDoNotRejectAnUnrelatedStrongPhrase() {
    assertThat(policy.acceptsNewPassword("unrelated-phrase-42", "a@example.test")).isTrue();
    assertThat(policy.acceptsNewPassword("99-99999-a-99999999", "a@example.test")).isFalse();
    assertThat(policy.acceptsNewPassword("unrelated-phrase-42", "x.y@example.test")).isTrue();
    assertThat(policy.acceptsNewPassword("99-99999-x.y-99999999", "x.y@example.test")).isFalse();
  }

  @Test
  void verifiesTheEntirePinnedCorpusAndFailsClosedOnMissingTruncatedOrCorruptData()
      throws Exception {
    Set<String> corpus = PasswordBlocklist.load();
    assertThat(corpus).hasSize(10000);
    assertThat(corpus).allMatch(entry -> entry.matches("[a-f0-9]{64}"));
    assertThatThrownBy(() -> PasswordBlocklist.readVerified(null))
        .isInstanceOf(IllegalStateException.class);
    byte[] original;
    try (var input = getClass().getResourceAsStream("/authentication/password-blocklist.sha256")) {
      original = java.util.Objects.requireNonNull(input).readAllBytes();
    }
    original[0] = (byte) (original[0] == 'a' ? 'b' : 'a');
    assertThatThrownBy(() -> PasswordBlocklist.readVerified(new ByteArrayInputStream(original)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                PasswordBlocklist.readVerified(
                    new ByteArrayInputStream("short".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalStateException.class);
  }
}
