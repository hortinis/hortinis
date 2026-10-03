package com.hortinis.sync.access;

/** Closed server modes; parsing supplies no default or fallback. */
public enum ServerAccessMode {
  SINGLE_USER_NO_AUTH("single-user-no-auth"),
  SINGLE_USER_AUTH("single-user-auth"),
  MULTI_USER_AUTH("multi-user-auth");

  private final String configuredValue;

  ServerAccessMode(String wireValue) {
    this.configuredValue = wireValue;
  }

  /** Returns the contract representation. */
  public String wireValue() {
    return configuredValue;
  }

  /** Rejects absent and unknown modes. */
  public static ServerAccessMode parse(String value) {
    for (ServerAccessMode mode : values()) {
      if (mode.configuredValue.equals(value)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("The access mode is invalid.");
  }
}
