package com.hortinis.sync.access;

/** Fixed denial that reveals no destination or admission state. */
public final class BindingPreconditionFailedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates a fixed generic failure without retained request or identity data. */
  public BindingPreconditionFailedException() {
    super("The binding precondition no longer holds.");
  }
}
