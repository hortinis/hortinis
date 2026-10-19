package com.hortinis.sync.authentication;

import java.util.concurrent.Semaphore;

/** Two synchronous SMTP calls per process, without a waiting queue or durable accounting. */
public final class ActionMailAdmission {
  private final Semaphore deliveries = new Semaphore(2);

  /** Only eligible issuance acquires a transport permit; unknown requests use no SMTP capacity. */
  public Permit reserve() {
    return new Permit(deliveries.tryAcquire());
  }

  /** Closing twice cannot enlarge transport capacity. */
  public final class Permit implements AutoCloseable {
    private final boolean admitted;
    private boolean closed;

    private Permit(boolean allowed) {
      admitted = allowed;
    }

    /** Whether this process has an immediate synchronous transport slot. */
    public boolean allowed() {
      return admitted;
    }

    @Override
    public synchronized void close() {
      if (!closed && admitted) {
        deliveries.release();
      }
      closed = true;
    }

    @Override
    public String toString() {
      return "MailPermit[redacted]";
    }
  }
}
