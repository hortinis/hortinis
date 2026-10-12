package com.hortinis.operator;

import java.nio.file.Path;
import java.util.UUID;

/** Closed host arguments contain no password, ticket secret or arbitrary Spring property. */
public record OperatorCommandOptions(Action action, Path directory, UUID account) {
  /** Rejects malformed arguments before starting any application context or database work. */
  public static OperatorCommandOptions parse(String[] args) {
    if (args == null
        || args.length < 3
        || args.length > 4
        || !"operator".equals(args[0])
        || args[1] == null) {
      throw invalid();
    }
    Action action =
        switch (args[1]) {
          case "setup-ticket" -> Action.SETUP;
          case "recovery-ticket" -> Action.RECOVERY;
          case "reconcile" -> Action.RECONCILE;
          default -> throw invalid();
        };
    Path directory = null;
    UUID account = null;
    for (int index = 2; index < args.length; index++) {
      String value = args[index];
      if (value == null
          || value.chars().anyMatch(character -> character < 32 || character == 127)) {
        throw invalid();
      }
      if (value.startsWith("--directory=") && directory == null) {
        directory = Path.of(value.substring("--directory=".length()));
        if (!directory.isAbsolute()) {
          throw invalid();
        }
        directory = directory.normalize();
      } else if (value.startsWith("--account=") && account == null) {
        String identifier = value.substring("--account=".length());
        account = UUID.fromString(identifier);
        if (!account.toString().equals(identifier)) {
          throw invalid();
        }
      } else {
        throw invalid();
      }
    }
    if (directory == null || (action == Action.RECOVERY) != (account != null)) {
      throw invalid();
    }
    return new OperatorCommandOptions(action, directory, account);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid operator command.");
  }

  @Override
  public String toString() {
    return "OperatorCommandOptions[redacted]";
  }

  /** Closed host capabilities; none grants an ordinary application session. */
  public enum Action {
    SETUP,
    RECOVERY,
    RECONCILE
  }
}
