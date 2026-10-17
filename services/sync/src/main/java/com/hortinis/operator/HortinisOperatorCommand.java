package com.hortinis.operator;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Explicit local process capability; this package is outside the web application's component scan.
 */
public final class HortinisOperatorCommand {
  private HortinisOperatorCommand() {}

  /**
   * A misplaced operator token still enters the closed parser instead of starting an HTTP server.
   */
  public static boolean isInvocation(String[] args) {
    return args != null
        && Arrays.stream(args)
            .anyMatch(
                value ->
                    "operator".equals(value) || value != null && value.startsWith("--operator"));
  }

  /** Last-resort process boundary also redacts unexpected errors before the JVM prints a trace. */
  public static void installUncaughtFailureBoundary() {
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> System.err.println("Operator command failed."));
  }

  /** Only fixed outcomes leave this process; no configuration value or exception is printed. */
  public static int run(String[] args, PrintStream output, PrintStream error) {
    try {
      OperatorCommandOptions.parse(args);
    } catch (IllegalArgumentException exception) {
      error.println("Invalid operator command.");
      return 2;
    }
    error.println("Operator authentication workflows are unavailable until S9-R is complete.");
    return 1;
  }
}
