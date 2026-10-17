package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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

  /** One bounded batch per category; authorization never waits for physical deletion. */
  public void purgeActions() {
    transactions.executeWithoutResult(
        status -> {
          var instant = clock.instant().truncatedTo(ChronoUnit.MICROS);
          var now = Timestamp.from(instant);
          jdbc.sql(
                  "WITH locked_accounts AS MATERIALIZED (SELECT a.account_id "
                      + "FROM authentication_account a WHERE EXISTS (SELECT 1 "
                      + "FROM authentication_action_token t WHERE t.account_id = a.account_id "
                      + "AND t.expires_at <= ?) ORDER BY a.account_id LIMIT 500 "
                      + "FOR UPDATE OF a SKIP LOCKED), expired AS (SELECT t.secret_verifier "
                      + "FROM authentication_action_token t JOIN locked_accounts a "
                      + "ON a.account_id = t.account_id WHERE t.expires_at <= ? "
                      + "ORDER BY t.expires_at, t.secret_verifier LIMIT 500) "
                      + "DELETE FROM authentication_action_token WHERE secret_verifier "
                      + "IN (SELECT secret_verifier FROM expired)")
              .params(now, now)
              .update();
          jdbc.sql(
                  "WITH overdue AS (SELECT account_id FROM authentication_account "
                      + "WHERE status = 'pending' AND created_at <= ? "
                      + "ORDER BY account_id LIMIT 500 FOR UPDATE SKIP LOCKED) "
                      + "DELETE FROM authentication_account WHERE account_id IN "
                      + "(SELECT account_id FROM overdue)")
              .param(Timestamp.from(instant.minus(Duration.ofDays(7))))
              .update();
        });
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
