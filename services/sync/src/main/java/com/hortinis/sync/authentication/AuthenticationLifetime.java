package com.hortinis.sync.authentication;

import java.time.Duration;

/** Dormant legacy host-ticket bound, removed with ticket storage in R4. */
public final class AuthenticationLifetime {
  public static final Duration TICKET_ABSOLUTE = Duration.ofMinutes(10);

  private AuthenticationLifetime() {}
}
