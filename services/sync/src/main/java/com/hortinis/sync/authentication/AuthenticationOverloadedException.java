package com.hortinis.sync.authentication;

/**
 * Fixed safe failure for exhausted admission; HTTP adapters never expose a credential or subject.
 */
public final class AuthenticationOverloadedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Contains only a fixed generic diagnostic. */
  public AuthenticationOverloadedException() {
    super("Authentication is temporarily unavailable.");
  }
}
