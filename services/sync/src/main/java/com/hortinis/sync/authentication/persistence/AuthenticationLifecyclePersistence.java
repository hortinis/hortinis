package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retention for application proofs and audit records; Spring owns every session lifecycle. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationLifecyclePersistence {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transactions;

  /** Shares the application datasource and commits each proof family independently. */
  public AuthenticationLifecyclePersistence(
      JdbcClient jdbc, Clock clock, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.clock = clock;
    transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Each record family commits independently; cleanup never holds locks across authentication
   * flows.
   */
  public boolean purge() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Authentication cleanup requires independent transactions.");
    }
    if (!jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton")
        .query(String.class)
        .optional()
        .filter("single-user-auth"::equals)
        .isPresent()) {
      return false;
    }
    var now = Timestamp.from(clock.instant());
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "DELETE FROM authentication_challenge c WHERE expires_at <= ? OR "
                        + "created_at > ? "
                        + "OR NOT EXISTS (SELECT 1 FROM spring_session_attributes a WHERE "
                        + "a.attribute_name = 'hortinis.authority' AND a.attribute_bytes = "
                        + "convert_to('v1:authority:' || c.session_reference::text, 'UTF8'))")
                .params(now, now)
                .update());
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "DELETE FROM authentication_operator_ticket t WHERE expires_at "
                        + "<= ? OR created_at > ? "
                        + "OR NOT EXISTS (SELECT 1 FROM technical_server_installation i WHERE "
                        + "i.server_instance_id = t.server_instance_id AND "
                        + "i.access_mode = t.access_mode) "
                        + "OR (t.account_id IS NOT NULL AND NOT EXISTS (SELECT 1 "
                        + "FROM authentication_account a "
                        + "WHERE a.account_id = t.account_id AND a.status = 'active')) "
                        + "OR (t.claimed_session_reference IS NOT NULL AND NOT "
                        + "EXISTS (SELECT 1 FROM "
                        + "spring_session_attributes a WHERE a.attribute_name = "
                        + "'hortinis.authority' "
                        + "AND a.attribute_bytes = convert_to('v1:authority:' || "
                        + "t.claimed_session_reference::text, 'UTF8')))")
                .params(now, now)
                .update());
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "DELETE FROM authentication_recovery_code WHERE expires_at <= ? "
                        + "OR created_at > ?")
                .params(now, now)
                .update());
    transactions.executeWithoutResult(
        status ->
            jdbc.sql("DELETE FROM authentication_security_event WHERE occurred_at <= ?")
                .param(Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .update());
    return true;
  }
}
