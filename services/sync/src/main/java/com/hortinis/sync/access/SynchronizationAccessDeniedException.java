package com.hortinis.sync.access;

/** Generic denial without scope, installation, or ownership information. */
public final class SynchronizationAccessDeniedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the fixed generic access denial. */
  public SynchronizationAccessDeniedException() {
    super("Access is denied.");
  }
}
