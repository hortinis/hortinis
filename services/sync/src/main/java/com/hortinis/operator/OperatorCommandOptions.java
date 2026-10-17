package com.hortinis.operator;

/** Closed host arguments never contain email, credentials, identifiers or Spring properties. */
public record OperatorCommandOptions(Action action) {
  /** Rejects all secret-bearing, retired and arbitrary property arguments. */
  public static OperatorCommandOptions parse(String[] args) {
    if (args == null || args.length != 2 || !"operator".equals(args[0]) || args[1] == null) {
      throw new IllegalArgumentException("Invalid operator command.");
    }
    return new OperatorCommandOptions(
        switch (args[1]) {
          case "invite" -> Action.INVITE;
          case "resend-invitation" -> Action.RESEND;
          case "cancel-invitation" -> Action.CANCEL;
          default -> throw new IllegalArgumentException("Invalid operator command.");
        });
  }

  /** The only authorized host capabilities; none grants a browser session. */
  public enum Action {
    INVITE,
    RESEND,
    CANCEL
  }
}
