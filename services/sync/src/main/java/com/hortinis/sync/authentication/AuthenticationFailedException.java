package com.hortinis.sync.authentication;

/** Generic failed proof; never retains a provider cause containing credential or client data. */
public final class AuthenticationFailedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Account existence, credential IDs and provider details do not enter the error. */
  public AuthenticationFailedException() {
    super("Authentication failed.");
  }
}
