package com.hortinis.sync.access;

/** Required access protocol state cannot safely be obtained. */
public final class SynchronizationAccessUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates a fixed generic failure without retained request or identity data. */
  public SynchronizationAccessUnavailableException() {
    super("The access service is unavailable.");
  }
}
