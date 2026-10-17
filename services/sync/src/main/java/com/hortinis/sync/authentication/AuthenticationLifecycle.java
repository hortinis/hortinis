package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;

/** Audit retention and transient admission cleanup; Spring owns native session expiry. */
public final class AuthenticationLifecycle implements SmartInitializingSingleton {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationLifecycle.class);
  private final AuthenticationLifecyclePersistence persistence;
  private final AuthenticationAttemptLimiter attempts;
  private final ActionMailAdmission mail;

  /** No partial-session, proof or ticket cleanup runs in the replacement graph. */
  public AuthenticationLifecycle(
      AuthenticationLifecyclePersistence persistence,
      AuthenticationAttemptLimiter attempts,
      ActionMailAdmission mail) {
    this.persistence = persistence;
    this.attempts = attempts;
    this.mail = mail;
  }

  @Override
  public void afterSingletonsInstantiated() {
    try {
      persistence.purge();
      persistence.purgeActions();
    } catch (DataAccessException | TransactionException exception) {
      throw new IllegalStateException("Authentication cleanup failed.");
    }
  }

  /** Transient admission memory is swept every minute, independently of audit retention. */
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void cleanupAttempts() {
    attempts.cleanup();
    mail.cleanup();
    try {
      persistence.purgeActions();
    } catch (DataAccessException | TransactionException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "authentication_cleanup_failed")
          .log("Authentication cleanup failed.");
    }
  }

  /**
   * Daily UTC retention retries only fixed diagnostic outcomes; startup handles overdue records.
   */
  @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
  public void scheduledPurge() {
    try {
      persistence.purge();
    } catch (DataAccessException | TransactionException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "authentication_cleanup_failed")
          .log("Authentication cleanup failed.");
    }
  }
}
