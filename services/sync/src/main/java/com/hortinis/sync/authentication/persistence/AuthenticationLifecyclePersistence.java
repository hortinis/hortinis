package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Purges application ceremony and recovery records; Spring owns native session expiry cleanup. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationLifecyclePersistence {
  private static final String AUTH_MODE = "single-user-auth";
  private static final Duration EVENT_RETENTION = Duration.ofDays(30);
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final AuthenticationSessionRemovalPersistence removal;

  /** Uses the same manager as protected publication, with a separate transaction per account. */
  public AuthenticationLifecyclePersistence(
      JdbcClient jdbc,
      Clock clock,
      PlatformTransactionManager transactionManager,
      AuthenticationNativeSessions nativeSessions) {
    this.jdbc = jdbc;
    this.clock = clock;
    removal = new AuthenticationSessionRemovalPersistence(jdbc, nativeSessions);
    transactions = new TransactionTemplate(transactionManager);
  }

  /** No enclosing transaction may retain an account/session lock across the sweep. */
  public boolean purge() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Authentication cleanup requires independent transactions.");
    }
    Boolean selected = transactions.execute(status -> purgeUnowned());
    if (!Boolean.TRUE.equals(selected)) {
      return false;
    }
    List<UUID> accounts =
        jdbc.sql("SELECT account_id FROM authentication_account ORDER BY account_id")
            .query(UUID.class)
            .list();
    for (UUID account : accounts) {
      transactions.executeWithoutResult(status -> purgeAccount(account));
    }
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql(
                  "DELETE FROM authentication_challenge c WHERE "
                      + "NOT EXISTS (SELECT 1 FROM authentication_session_authority a "
                      + "WHERE a.session_reference = c.session_reference) "
                      + "AND NOT EXISTS (SELECT 1 FROM spring_session_attributes a WHERE "
                      + "a.attribute_name = 'hortinis.authority' "
                      + "AND a.attribute_bytes = convert_to('v1:authority:' || "
                      + "c.session_reference::text, 'UTF8'))")
              .update();
          jdbc.sql("DELETE FROM authentication_security_event WHERE occurred_at <= ?")
              .param(Timestamp.from(clock.instant().minus(EVENT_RETENTION)))
              .update();
        });
    return true;
  }

  private boolean purgeUnowned() {
    var installation =
        jdbc.sql(
                "SELECT server_instance_id, access_mode FROM technical_server_installation "
                    + "WHERE singleton FOR UPDATE")
            .query(
                (row, number) -> new Installation(row.getObject(1, UUID.class), row.getString(2)))
            .optional();
    if (installation.isEmpty() || !AUTH_MODE.equals(installation.orElseThrow().mode())) {
      return false;
    }
    List<AuthenticationSession> parents = parents(null);
    Instant now = clock.instant();
    for (AuthenticationSession parent : parents) {
      if (!parent.isUnexpired(now)) {
        removal.remove(parent.reference());
      }
    }
    jdbc.sql(
            "DELETE FROM authentication_challenge c WHERE c.account_id IS NULL "
                + "AND (c.expires_at <= ? OR c.created_at > ?)")
        .params(Timestamp.from(now), Timestamp.from(now))
        .update();
    jdbc.sql(
            "DELETE FROM authentication_operator_ticket WHERE account_id IS NULL AND "
                + "(expires_at <= ? OR created_at > ? "
                + "OR server_instance_id <> ? OR access_mode <> ?)")
        .params(
            Timestamp.from(now),
            Timestamp.from(now),
            installation.orElseThrow().instance(),
            AUTH_MODE)
        .update();
    return true;
  }

  private void purgeAccount(UUID accountId) {
    var selected = lockAccount(accountId);
    if (selected.isEmpty()) {
      return;
    }
    Account account = selected.orElseThrow();
    List<AuthenticationSession> parents = parents(accountId);
    Instant now = clock.instant();
    for (AuthenticationSession parent : parents) {
      if (!"active".equals(account.status())
          || !AUTH_MODE.equals(account.mode())
          || parent.epoch() != account.epoch()
          || !parent.isUnexpired(now)) {
        removal.remove(parent.reference());
      }
    }
    jdbc.sql(
            "DELETE FROM authentication_challenge c WHERE c.account_id = ? "
                + "AND (c.expires_at <= ? OR c.created_at > ?)")
        .params(accountId, Timestamp.from(now), Timestamp.from(now))
        .update();
    jdbc.sql(
            "DELETE FROM authentication_recovery_code WHERE account_id = ? "
                + "AND (expires_at <= ? OR created_at > ?)")
        .params(accountId, Timestamp.from(now), Timestamp.from(now))
        .update();
    jdbc.sql(
            "DELETE FROM authentication_operator_ticket t "
                + "USING authentication_account a, technical_server_installation i "
                + "WHERE t.account_id = ? AND a.account_id = t.account_id "
                + "AND i.server_instance_id = a.server_instance_id AND "
                + "(t.expires_at <= ? OR t.created_at > ? OR t.access_mode <> i.access_mode "
                + "OR t.server_instance_id <> i.server_instance_id OR a.status <> 'active')")
        .params(accountId, Timestamp.from(now), Timestamp.from(now))
        .update();
  }

  private List<AuthenticationSession> parents(UUID account) {
    String ownerPredicate = account == null ? "account_id IS NULL" : "account_id = ?";
    var query =
        jdbc.sql(
            "SELECT "
                + AuthenticationSessionRows.COLUMNS
                + " FROM authentication_session_authority WHERE "
                + ownerPredicate
                + " ORDER BY session_reference FOR UPDATE");
    if (account != null) {
      query = query.param(account);
    }
    return query.query(AuthenticationSessionRows::read).list();
  }

  private java.util.Optional<Account> lockAccount(UUID accountId) {
    return jdbc.sql(
            "SELECT a.status, a.authority_epoch, i.access_mode "
                + "FROM authentication_account a JOIN technical_server_installation i "
                + "ON i.server_instance_id = a.server_instance_id WHERE a.account_id = ? "
                + "FOR UPDATE OF a")
        .param(accountId)
        .query((row, number) -> new Account(row.getString(1), row.getLong(2), row.getString(3)))
        .optional();
  }

  private record Installation(UUID instance, String mode) {
    @Override
    public String toString() {
      return "Installation[redacted]";
    }
  }

  private record Account(String status, long epoch, String mode) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
