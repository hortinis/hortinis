package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ordered physical cleanup; runtime startup and scheduling are responsible for invoking sweeps. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationLifecyclePersistence {
  private static final String AUTH_MODE = "single-user-auth";
  private static final Duration EVENT_RETENTION = Duration.ofDays(30);
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final ConversionService attributes = SessionAttributeCodec.conversionService();

  /** Uses the same manager as protected publication, with a separate transaction per account. */
  public AuthenticationLifecyclePersistence(
      JdbcClient jdbc, Clock clock, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.clock = clock;
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
                  "DELETE FROM spring_session s WHERE NOT EXISTS ("
                      + "SELECT 1 FROM spring_session_attributes t "
                      + "JOIN authentication_session_authority a ON t.attribute_bytes = "
                      + "convert_to('v1:authority:' || a.session_reference::text, 'UTF8') "
                      + "WHERE t.session_primary_id = s.primary_id AND "
                      + "t.attribute_name = 'hortinis.authority' "
                      + "AND a.session_verifier = "
                      + "encode(sha256(convert_to(s.session_id, 'UTF8')), 'hex'))")
              .update();
          jdbc.sql("DELETE FROM authentication_security_event WHERE occurred_at <= ?")
              .param(Timestamp.from(clock.instant().minus(EVENT_RETENTION)))
              .update();
        });
    return true;
  }

  /** Targeted next-access cleanup takes only a trusted transport digest, never a wire verifier. */
  public boolean purgeAccess(String verifier) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Authentication cleanup requires independent transactions.");
    }
    if (verifier != null && !verifier.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid trusted transport digest.");
    }
    return Boolean.TRUE.equals(transactions.execute(status -> purgeAccessLocked(verifier)));
  }

  private boolean purgeAccessLocked(String verifier) {
    boolean selected =
        jdbc.sql(
                "SELECT access_mode = ? FROM technical_server_installation "
                    + "WHERE singleton FOR UPDATE")
            .param(AUTH_MODE)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (!selected || verifier == null) {
      return selected;
    }
    var preliminary =
        jdbc.sql(
                "SELECT "
                    + AuthenticationSessionRows.COLUMNS
                    + " FROM authentication_session_authority WHERE session_verifier = ?")
            .param(verifier)
            .query(AuthenticationSessionRows::read)
            .optional();
    if (preliminary.isPresent()) {
      var before = preliminary.orElseThrow();
      var account =
          before.account() == null
              ? java.util.Optional.<Account>empty()
              : jdbc.sql(
                      "SELECT a.status, a.authority_epoch, i.access_mode FROM "
                          + "authentication_account a "
                          + "JOIN technical_server_installation i ON "
                          + "i.server_instance_id = a.server_instance_id "
                          + "WHERE a.account_id = ? FOR UPDATE OF a")
                  .param(before.account())
                  .query(
                      (row, number) ->
                          new Account(row.getString(1), row.getLong(2), row.getString(3)))
                  .optional();
      var current =
          jdbc.sql(
                  "SELECT "
                      + AuthenticationSessionRows.COLUMNS
                      + " FROM authentication_session_authority WHERE "
                      + "session_verifier = ? FOR UPDATE")
              .param(verifier)
              .query(AuthenticationSessionRows::read)
              .optional();
      Instant now = clock.instant();
      if (current.isPresent()) {
        var parent = current.orElseThrow();
        boolean owned =
            parent.account() == null
                || account.isPresent()
                    && "active".equals(account.orElseThrow().status())
                    && AUTH_MODE.equals(account.orElseThrow().mode())
                    && parent.epoch() == account.orElseThrow().epoch()
                    && java.util.Objects.equals(parent.account(), before.account());
        if (!owned || !timeIsCurrent(parent, now)) {
          deleteParent(parent.reference());
        } else {
          jdbc.sql(
                  "DELETE FROM authentication_challenge WHERE session_reference = ? "
                      + "AND (expires_at <= ? OR created_at > ?)")
              .params(parent.reference(), Timestamp.from(now), Timestamp.from(now))
              .update();
        }
      }
      if (before.account() != null) {
        jdbc.sql(
                "DELETE FROM authentication_recovery_code WHERE account_id = ? "
                    + "AND (expires_at <= ? OR created_at > ?)")
            .params(before.account(), Timestamp.from(now), Timestamp.from(now))
            .update();
      }
    }
    jdbc.sql(
            "DELETE FROM spring_session s WHERE "
                + "encode(sha256(convert_to(s.session_id, 'UTF8')), 'hex') = ? "
                + "AND NOT EXISTS (SELECT 1 FROM "
                + "spring_session_attributes t JOIN authentication_session_authority a "
                + "ON t.attribute_bytes = convert_to('v1:authority:' || "
                + "a.session_reference::text, 'UTF8') "
                + "WHERE t.session_primary_id = s.primary_id AND "
                + "t.attribute_name = 'hortinis.authority' "
                + "AND a.session_verifier = ?)")
        .params(verifier, verifier)
        .update();
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
      if (!timeIsCurrent(parent, now)) {
        deleteParent(parent.reference());
      }
    }
    jdbc.sql(
            "DELETE FROM authentication_challenge c USING authentication_session_authority s "
                + "WHERE c.session_reference = s.session_reference AND s.account_id IS NULL "
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
    var selected =
        jdbc.sql(
                "SELECT a.status, a.authority_epoch, i.access_mode "
                    + "FROM authentication_account a JOIN technical_server_installation i "
                    + "ON i.server_instance_id = a.server_instance_id WHERE a.account_id = ? "
                    + "FOR UPDATE OF a")
            .param(accountId)
            .query((row, number) -> new Account(row.getString(1), row.getLong(2), row.getString(3)))
            .optional();
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
          || !timeIsCurrent(parent, now)) {
        deleteParent(parent.reference());
      }
    }
    jdbc.sql(
            "DELETE FROM authentication_challenge c USING authentication_session_authority s "
                + "WHERE c.session_reference = s.session_reference AND s.account_id = ? "
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

  private static boolean timeIsCurrent(AuthenticationSession parent, Instant now) {
    return parent.isUnexpired(now)
        && (!"full".equals(parent.state()) || parent.hasCurrentFactors(now));
  }

  private void deleteParent(UUID reference) {
    byte[] encoded = attributes.convert(new SessionAuthorityReference(reference), byte[].class);
    jdbc.sql(
            "DELETE FROM spring_session WHERE primary_id IN "
                + "(SELECT session_primary_id FROM spring_session_attributes "
                + "WHERE attribute_bytes = ?)")
        .param(encoded)
        .update();
    jdbc.sql("DELETE FROM authentication_session_authority WHERE session_reference = ?")
        .param(reference)
        .update();
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
