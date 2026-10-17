package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {
  @Test
  void canonicalizesWholeAsciiAddressWithoutProviderAliasRewriting() {
    assertThat(EmailAddress.canonical("A.B+Tag@Example.TEST")).isEqualTo("a.b+tag@example.test");
    assertThat(EmailAddress.canonical("a@xn--synthetic-9za.test"))
        .isEqualTo("a@xn--synthetic-9za.test");
    assertThat(EmailAddress.canonical("x".repeat(64) + "@example.test")).startsWith("x".repeat(64));
    String maximum =
        "x".repeat(64) + "@" + "d".repeat(63) + "." + "d".repeat(63) + "." + "d".repeat(61);
    assertThat(maximum).hasSize(254);
    assertThat(EmailAddress.canonical(maximum)).isEqualTo(maximum);
    assertThatThrownBy(() -> EmailAddress.canonical(maximum + "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " a@example.test",
        "a@example.test ",
        "a@example.test\n",
        "a..b@example.test",
        ".a@example.test",
        "a.@example.test",
        "a@example.test.",
        "a@-example.test",
        "a@example-.test",
        "a@localhost",
        "a@[127.0.0.1]",
        "a@éxample.test",
        "é@example.test",
        "\"a\"@example.test",
        "a(comment)@example.test"
      })
  void rejectsUnsupportedExactText(String value) {
    assertThatThrownBy(() -> EmailAddress.canonical(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid email address.");
  }
}
