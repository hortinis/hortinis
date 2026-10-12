package com.hortinis.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Closed host argument parsing is independent of database, framework startup and secret material.
 */
class OperatorCommandOptionsTest {
  private static final String RECOVERY = "recovery-ticket";
  private static final String SETUP = "setup-ticket";
  private static final String OPERATOR = "operator";
  private static final String DIRECTORY = "--directory=/tmp/hortinis-operator";
  private static final String ACCOUNT = "019a0b2d-9440-7ce7-b1e4-bfc0112c9f52";
  private static final String SECRET_CANARY = "synthetic-command-secret-canary";

  @Test
  void acceptsOnlyPurposeAppropriateNonSecretOptionsAndRedactsTheirDiagnosticRepresentation() {
    var setup = OperatorCommandOptions.parse(new String[] {OPERATOR, SETUP, DIRECTORY});
    assertThat(setup.action()).isEqualTo(OperatorCommandOptions.Action.SETUP);
    assertThat(setup.directory()).isEqualTo(Path.of("/tmp/hortinis-operator"));
    assertThat(setup.account()).isNull();
    var recovery =
        OperatorCommandOptions.parse(
            new String[] {OPERATOR, RECOVERY, "--account=" + ACCOUNT, DIRECTORY});
    assertThat(recovery.account()).isEqualTo(UUID.fromString(ACCOUNT));
    assertThat(recovery.action()).isEqualTo(OperatorCommandOptions.Action.RECOVERY);
    assertThat(recovery.toString()).doesNotContain(ACCOUNT, recovery.directory().toString());
    assertThat(
            OperatorCommandOptions.parse(new String[] {OPERATOR, "reconcile", DIRECTORY}).action())
        .isEqualTo(OperatorCommandOptions.Action.RECONCILE);
  }

  @ParameterizedTest
  @MethodSource("invalidArguments")
  void rejectsIncompleteUnknownDuplicatedMisplacedAndSecretBearingArgumentsBeforeContextStartup(
      String[] args) {
    assertThatThrownBy(() -> OperatorCommandOptions.parse(args))
        .isInstanceOf(IllegalArgumentException.class);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    int status =
        HortinisOperatorCommand.run(
            args,
            new PrintStream(output, true, StandardCharsets.UTF_8),
            new PrintStream(error, true, StandardCharsets.UTF_8));
    assertThat(status).isEqualTo(2);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(error.toString(StandardCharsets.UTF_8)).isEqualTo("Invalid operator command.\n");
  }

  @Test
  void misplacedAndMistypedOperatorMarkersCannotFallThroughIntoOrdinaryWebStartup() {
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {OPERATOR})).isTrue();
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--server.port=0", OPERATOR}))
        .isTrue();
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--operator=setup-ticket"}))
        .isTrue();
    assertThat(HortinisOperatorCommand.isInvocation(new String[] {"--server.port=0"})).isFalse();
  }

  static Stream<Arguments> invalidArguments() {
    return Stream.<String[]>of(
            new String[] {},
            new String[] {OPERATOR},
            new String[] {OPERATOR, null, DIRECTORY},
            new String[] {OPERATOR, SETUP},
            new String[] {OPERATOR, "unknown", DIRECTORY},
            new String[] {OPERATOR, SETUP, "--directory=relative"},
            new String[] {OPERATOR, SETUP, DIRECTORY, DIRECTORY},
            new String[] {OPERATOR, SETUP, DIRECTORY, "--password=" + SECRET_CANARY},
            new String[] {OPERATOR, RECOVERY, DIRECTORY},
            new String[] {OPERATOR, SETUP, DIRECTORY, "--account=" + ACCOUNT},
            new String[] {OPERATOR, RECOVERY, DIRECTORY, "--account=1-1-1-1-1"},
            new String[] {
              OPERATOR,
              RECOVERY,
              DIRECTORY,
              "--account=" + ACCOUNT.toUpperCase(java.util.Locale.ROOT)
            },
            new String[] {OPERATOR, "reconcile", DIRECTORY, "--ticket=" + SECRET_CANARY},
            new String[] {OPERATOR, SETUP, "--directory=/tmp/unsafe\n" + SECRET_CANARY},
            new String[] {"--server.port=0", OPERATOR, SETUP, DIRECTORY},
            new String[] {"--operator=setup-ticket", DIRECTORY})
        .map(value -> Arguments.of((Object) value));
  }
}
