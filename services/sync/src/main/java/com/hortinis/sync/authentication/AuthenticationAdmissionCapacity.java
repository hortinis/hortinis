package com.hortinis.sync.authentication;

/** One combined bound for process-local authentication and mail HMAC keys. */
public final class AuthenticationAdmissionCapacity {
  private final int maximum;
  private int retained;

  /** Production authentication and mail limiters share this instance. */
  public AuthenticationAdmissionCapacity() {
    this(4096);
  }

  AuthenticationAdmissionCapacity(int maximum) {
    if (maximum < 1 || maximum > 4096) {
      throw new IllegalArgumentException("Invalid admission key capacity.");
    }
    this.maximum = maximum;
  }

  synchronized boolean acquire(int count) {
    if (retained + count > maximum) {
      return false;
    }
    retained += count;
    return true;
  }

  synchronized void release() {
    retained--;
  }
}
