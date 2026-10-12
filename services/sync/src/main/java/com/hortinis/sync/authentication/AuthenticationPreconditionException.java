package com.hortinis.sync.authentication;

/** Closed state conflict; never identifies an account, credential or supplied target. */
public final class AuthenticationPreconditionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** A caller may retry only after re-observing the authorized state. */
  public AuthenticationPreconditionException() {
    super("The authentication precondition no longer holds.");
  }
}
