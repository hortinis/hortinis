package com.hortinis.sync.authentication;

/** Closed ceremony purposes; neither a challenge nor its purpose grants content authority. */
public enum AuthenticationChallengePurpose {
  SETUP("setup"),
  LOGIN("login"),
  STEP_UP("step-up"),
  CREDENTIAL("credential"),
  RECOVER_PASSWORD("recover-password"),
  RECOVER_PASSKEY("recover-passkey"),
  OPERATOR("operator");

  private final String databaseValue;

  AuthenticationChallengePurpose(String wireValue) {
    databaseValue = wireValue;
  }

  /** Value stored in the constrained purpose column; not derived from unchecked request text. */
  public String wireValue() {
    return databaseValue;
  }

  /** Checks the restricted ceremony that may request this type of proof. */
  public boolean permits(String state, String recoveryKind, boolean continuingFullLogin) {
    return switch (this) {
      case SETUP -> "setup".equals(state);
      case LOGIN -> "password".equals(state) && !continuingFullLogin;
      case STEP_UP -> "password".equals(state) && continuingFullLogin;
      case CREDENTIAL -> "full".equals(state);
      case RECOVER_PASSWORD -> "recovery".equals(state) && "password".equals(recoveryKind);
      case RECOVER_PASSKEY -> "recovery".equals(state) && "passkey".equals(recoveryKind);
      case OPERATOR -> "recovery".equals(state) && "operator".equals(recoveryKind);
    };
  }
}
