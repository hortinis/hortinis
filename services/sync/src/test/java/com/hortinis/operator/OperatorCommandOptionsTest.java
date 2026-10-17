package com.hortinis.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R4 command bounds fail before any context, database, file or SMTP activity. */
class OperatorCommandOptionsTest {
  private static final String OPERATOR = "operator";

  @ParameterizedTest
  @ValueSource(strings = {"invite", "resend-invitation", "cancel-invitation"})
  void acceptsOnlyNewHostCapabilities(String action) {
    assertThat(OperatorCommandOptions.parse(new String[] {OPERATOR, action})).isNotNull();
    assertThatThrownBy(
            () -> OperatorCommandOptions.parse(new String[] {OPERATOR, action, "--email=secret"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"setup-ticket", "recovery-ticket", "reconcile", "unknown", "--operator=invite"})
  void retiredCommandsCannotStartContext(String action) {
    var output = new ByteArrayOutputStream();
    int code =
        HortinisOperatorCommand.run(
            new String[] {OPERATOR, action},
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(output),
            new PrintStream(output));
    assertThat(code).isEqualTo(2);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .isEqualTo("Invalid operator command or input.\n");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " a@example.test\n",
        "a@example.test\nextra",
        "a@example.test\n\n",
        "a@é.test\n",
        "a@example.test\r",
        "canary"
      })
  void rejectsInvalidSingleLineWithoutReflectingInput(String input) {
    invalid(input);
  }

  @Test
  void inputIsBoundedAndOperatorMarkersCannotFallThroughToWebStartup() {
    invalid("a".repeat(257));
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--server.port=0", OPERATOR}))
        .isTrue();
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--operator=invite"})).isTrue();
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--server.port=0"})).isFalse();
  }

  private static void invalid(String input) {
    var output = new ByteArrayOutputStream();
    int code =
        HortinisOperatorCommand.run(
            new String[] {OPERATOR, "invite"},
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(output),
            new PrintStream(output));
    assertThat(code).isEqualTo(2);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .isEqualTo("Invalid operator command or input.\n");
  }
}
