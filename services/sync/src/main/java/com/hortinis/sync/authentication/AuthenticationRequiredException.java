package com.hortinis.sync.authentication;

/** Fixed generic denial when no current complete account/session authority exists. */
public final class AuthenticationRequiredException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Carries no subject, cookie, verifier or provider detail. */
  public AuthenticationRequiredException() {
    super("Authentication is required.");
  }
}
