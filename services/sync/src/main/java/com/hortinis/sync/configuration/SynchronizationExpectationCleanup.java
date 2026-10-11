package com.hortinis.sync.configuration;

import com.hortinis.sync.persistence.SynchronizationExpectationPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded-lifetime maintenance reads no garden data and emits no protocol identifiers. */
@Configuration(proxyBeanMethods = false)
@SynchronizationPersistenceEnabled
@EnableScheduling
public class SynchronizationExpectationCleanup {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(SynchronizationExpectationCleanup.class);
  private final SynchronizationExpectationPersistence expectations;

  /** Uses only the protocol-metadata cleanup adapter. */
  public SynchronizationExpectationCleanup(SynchronizationExpectationPersistence expectations) {
    this.expectations = expectations;
  }

  /** Expired expectations are unusable even if cleanup is temporarily unavailable. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void purge() {
    try {
      expectations.purgeExpired();
    } catch (DataAccessException exception) {
      // Scheduler stack traces could expose provider diagnostics; retain one fixed event only.
      LOGGER
          .atWarn()
          .addKeyValue("event", "expectation_cleanup_unavailable")
          .log("Protocol metadata cleanup is unavailable");
    }
  }
}
