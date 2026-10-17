package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Thirty-day minimized security-event retention; no legacy ceremony lifecycle. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationLifecyclePersistence {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transactions;

  /** Retention shares application JDBC; native Spring Session performs idle cleanup. */
  public AuthenticationLifecyclePersistence(
      JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactions);
  }

  /** Startup overdue retention and daily UTC purge. */
  public void purge() {
    transactions.executeWithoutResult(
        status ->
            jdbc.sql("DELETE FROM authentication_security_event WHERE occurred_at <= ?")
                .param(Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .update());
  }
}
