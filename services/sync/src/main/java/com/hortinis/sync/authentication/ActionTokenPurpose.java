package com.hortinis.sync.authentication;

import java.time.Duration;

/** Closed application actions; neither purpose grants a login or session. */
public enum ActionTokenPurpose {
  INVITATION("invitation", Duration.ofHours(24), "/accept-invitation"),
  RESET("reset", Duration.ofMinutes(10), "/reset-password");

  private final String databaseValue;
  private final Duration maximumLifetime;
  private final String pagePath;

  ActionTokenPurpose(String value, Duration lifetime, String path) {
    this.databaseValue = value;
    this.maximumLifetime = lifetime;
    this.pagePath = path;
  }

  /** Fixed database purpose, never selected by a request field. */
  public String value() {
    return databaseValue;
  }

  /** Accepted maximum lifetime. */
  public Duration lifetime() {
    return maximumLifetime;
  }

  /** First-party page path; opening it has no mutation semantics. */
  public String path() {
    return pagePath;
  }
}
