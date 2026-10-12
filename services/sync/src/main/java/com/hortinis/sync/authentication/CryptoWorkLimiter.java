package com.hortinis.sync.authentication;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Bounds expensive work without waiting queues, policy downgrade or caller-dependent admission. */
public final class CryptoWorkLimiter {
  private final Semaphore permits = new Semaphore(2);

  /** Runs at most two immediate jobs and releases capacity on every exit path. */
  public <T> T execute(Supplier<T> work) {
    if (!permits.tryAcquire()) {
      throw new AuthenticationOverloadedException();
    }
    try {
      return work.get();
    } finally {
      permits.release();
    }
  }
}
