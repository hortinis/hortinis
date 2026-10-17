package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared action storage; callers own the transaction, including future credential mutations. */
@Repository
@SynchronizationPersistenceEnabled
public class ActionTokenPersistence {
  private static final Duration PENDING_LIFETIME = Duration.ofDays(7);
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Uses the application datasource and trusted UTC clock. */
  public ActionTokenPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Replaces the current purpose after locking and rechecking the stored account. */
  public IssuedToken issue(UUID account, ActionTokenPurpose purpose) {
    requireTransaction();
    Account selected = lockEligible(account, purpose);
    Instant now = now();
    String secret = AuthenticationSecrets.randomValue();
    Instant expiry = now.plus(purpose.lifetime());
    if (purpose == ActionTokenPurpose.INVITATION) {
      Instant deadline = selected.created().plus(PENDING_LIFETIME);
      if (deadline.isBefore(expiry)) {
        expiry = deadline;
      }
    }
    if (!now.isBefore(expiry)) {
      throw new AuthenticationPreconditionException();
    }
    jdbc.sql(
            "INSERT INTO authentication_action_token "
                + "(account_id, server_instance_id, purpose, secret_verifier, "
                + "created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (account_id, purpose) DO UPDATE SET "
                + "secret_verifier = EXCLUDED.secret_verifier, created_at = EXCLUDED.created_at, "
                + "expires_at = EXCLUDED.expires_at")
        .params(
            account,
            selected.installation(),
            purpose.value(),
            AuthenticationSecrets.digest(secret),
            Timestamp.from(now),
            Timestamp.from(expiry))
        .update();
    return new IssuedToken(selected.delivery(), secret, purpose, expiry);
  }

  /** Account is locked before the token; a rollback restores the usable verifier. */
  public ConsumedToken consume(ActionTokenPurpose purpose, String secret) {
    requireTransaction();
    if (!AuthenticationSecrets.isRandomValue(secret)) {
      throw new AuthenticationPreconditionException();
    }
    String digest = AuthenticationSecrets.digest(secret);
    UUID account =
        jdbc.sql(
                "SELECT account_id FROM authentication_action_token "
                    + "WHERE purpose = ? AND secret_verifier = ?")
            .params(purpose.value(), digest)
            .query(UUID.class)
            .optional()
            .orElseThrow(AuthenticationPreconditionException::new);
    Account selected = lockEligible(account, purpose);
    Instant now = now();
    int consumed =
        jdbc.sql(
                "DELETE FROM authentication_action_token WHERE account_id = ? "
                    + "AND server_instance_id = ? AND purpose = ? AND secret_verifier = ? "
                    + "AND created_at <= ? AND expires_at > ?")
            .params(
                account,
                selected.installation(),
                purpose.value(),
                digest,
                Timestamp.from(now),
                Timestamp.from(now))
            .update();
    if (consumed == 0) {
      throw new AuthenticationPreconditionException();
    }
    return new ConsumedToken(account, selected.installation(), selected.version());
  }

  /** Used by future account mutations in the same account-locked transaction. */
  public void invalidate(UUID account) {
    requireTransaction();
    jdbc.sql("SELECT account_id FROM authentication_account WHERE account_id = ? FOR UPDATE")
        .param(account)
        .query(UUID.class)
        .optional()
        .orElseThrow(AuthenticationPreconditionException::new);
    jdbc.sql("DELETE FROM authentication_action_token WHERE account_id = ?")
        .param(account)
        .update();
  }

  private Account lockEligible(UUID account, ActionTokenPurpose purpose) {
    Account selected =
        jdbc.sql(
                "SELECT a.server_instance_id, a.delivery_email, a.status, "
                    + "a.created_at, a.verified_at IS NOT NULL AND a.password_verifier "
                    + "IS NOT NULL, "
                    + "a.security_version FROM authentication_account a "
                    + "JOIN technical_server_installation i ON i.server_instance_id = "
                    + "a.server_instance_id "
                    + "JOIN technical_synchronization_scope s ON s.synchronization_scope_id "
                    + "= a.synchronization_scope_id AND s.server_instance_id = "
                    + "a.server_instance_id "
                    + "WHERE a.account_id = ? AND i.access_mode = 'single-user-auth' "
                    + "AND s.technical_default "
                    + "AND (SELECT count(*) FROM authentication_account) = 1 "
                    + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1 "
                    + "FOR UPDATE OF a")
            .param(account)
            .query(
                (row, number) ->
                    new Account(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        row.getTimestamp(4).toInstant(),
                        row.getBoolean(5),
                        row.getLong(6)))
            .optional()
            .orElseThrow(AuthenticationPreconditionException::new);
    Instant now = now();
    boolean eligible =
        purpose == ActionTokenPurpose.INVITATION
            ? "pending".equals(selected.status())
                && now.isBefore(selected.created().plus(PENDING_LIFETIME))
            : "active".equals(selected.status()) && selected.verified();
    if (!eligible || now.isBefore(selected.created())) {
      throw new AuthenticationPreconditionException();
    }
    EmailAddress.canonical(selected.delivery());
    return selected;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Action tokens require a writable transaction.");
    }
  }

  /** Transient delivery material; never a persistence model or diagnostic payload. */
  public record IssuedToken(
      String recipient, String secret, ActionTokenPurpose purpose, Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedToken[redacted]";
    }
  }

  /** Bound identity for the caller's subsequent mutation, never an authenticated principal. */
  public record ConsumedToken(UUID account, UUID installation, long securityVersion) {
    @Override
    public String toString() {
      return "ConsumedToken[redacted]";
    }
  }

  private record Account(
      UUID installation,
      String delivery,
      String status,
      Instant created,
      boolean verified,
      long version) {
    @Override
    public String toString() {
      return "ActionAccount[redacted]";
    }
  }
}
