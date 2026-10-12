package com.hortinis.sync.authentication;

import com.hortinis.sync.access.SynchronizationAccessUnavailableException;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;

/**
 * Startup/periodic/next-access cleanup adapter; no cleanup result grants or caches account
 * authority.
 */
public final class AuthenticationLifecycle implements SmartInitializingSingleton {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationLifecycle.class);
  private final AuthenticationLifecyclePersistence persistence;
  private final AuthenticationOperatorTickets tickets;
  private final AuthenticationAttemptLimiter attempts;
  private final ReentrantLock guard = new ReentrantLock();
  private final AtomicBoolean initialized = new AtomicBoolean();

  /** Constructed only for selected authentication; the runtime configuration owns scheduling. */
  public AuthenticationLifecycle(
      AuthenticationLifecyclePersistence persistence,
      AuthenticationOperatorTickets tickets,
      AuthenticationAttemptLimiter attempts) {
    this.persistence = persistence;
    this.tickets = tickets;
    this.attempts = attempts;
  }

  /** Completes before Spring starts its web-server lifecycle, or fails context initialization. */
  @Override
  public void afterSingletonsInstantiated() {
    guard.lock();
    try {
      sweep();
      initialized.set(true);
    } finally {
      guard.unlock();
    }
  }

  /**
   * The selected runtime enables a thirty-second tick; known failures retry with fixed diagnostics.
   */
  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void scheduledSweep() {
    if (!initialized.get() || !guard.tryLock()) {
      return;
    }
    try {
      sweep();
    } catch (DataAccessException
        | TransactionException
        | AuthenticationFailedException
        | SynchronizationAccessUnavailableException
        | IllegalStateException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "authentication_cleanup_failed")
          .log("Authentication cleanup failed.");
    } finally {
      guard.unlock();
    }
  }

  /**
   * Cleanup commits independently before request authority resolution; a raw cookie is never
   * retained.
   */
  public void onAccess(String transportDigest) {
    if (!initialized.get()) {
      throw new SynchronizationAccessUnavailableException();
    }
    guard.lock();
    try {
      if (!persistence.purgeAccess(transportDigest)) {
        throw new SynchronizationAccessUnavailableException();
      }
      tickets.reconcile();
    } finally {
      try {
        attempts.cleanup();
      } finally {
        guard.unlock();
      }
    }
  }

  private void sweep() {
    try {
      if (!persistence.purge()) {
        throw new SynchronizationAccessUnavailableException();
      }
      tickets.reconcile();
    } finally {
      attempts.cleanup();
    }
  }
}
