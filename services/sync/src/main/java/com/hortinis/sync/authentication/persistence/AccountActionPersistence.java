package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Credential snapshots are preparation inputs; only locked current state permits mutation. */
@Repository
@SynchronizationPersistenceEnabled
public class AccountActionPersistence {
  private static final int SINGLE_ACCOUNT = 1;
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Shares application JDBC and the trusted clock. */
  public AccountActionPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Reads password-policy context without locking or consuming the action. */
  public Snapshot forToken(ActionTokenPurpose purpose, String secret) {
    return jdbc.sql(
            "SELECT a.account_id, a.canonical_email, a.security_version, a.password_verifier "
                + "FROM authentication_account a JOIN authentication_action_token t "
                + "ON t.account_id = a.account_id "
                + "WHERE t.purpose = ? AND t.secret_verifier = ?")
        .params(purpose.value(), AuthenticationSecrets.digest(secret))
        .query(
            (row, number) ->
                new Snapshot(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    row.getLong(3),
                    row.getString(4)))
        .optional()
        .orElseThrow(AuthenticationPreconditionException::new);
  }

  /** Reads a credential snapshot before bounded password work. */
  public Snapshot account(UUID account) {
    return read(account, false);
  }

  /** Call before locking a native session, including for authenticated password change. */
  public void lockUnchanged(Snapshot snapshot) {
    requireTransaction();
    if (!snapshot.equals(read(snapshot.account(), true))) {
      throw new AuthenticationPreconditionException();
    }
  }

  /** Selects eligible reset candidates only after mail admission. */
  public Optional<UUID> resetTarget(String canonical) {
    return jdbc.sql(
            "SELECT account_id FROM authentication_account WHERE canonical_email = ? "
                + "AND status = 'active' AND verified_at IS NOT NULL AND "
                + "password_verifier IS NOT NULL")
        .param(canonical)
        .query(UUID.class)
        .optional();
  }

  /** Token storage has already locked and checked eligibility and ownership. */
  public void replace(Snapshot snapshot, String verifier, boolean activation) {
    requireTransaction();
    int changed =
        jdbc.sql(
                "UPDATE authentication_account SET password_verifier = ?, "
                    + (activation
                        ? "status = 'active', verified_at = ? "
                        : "security_version = security_version + 1 ")
                    + "WHERE account_id = ? AND security_version = ?")
            .params(
                activation
                    ? new Object[] {
                      verifier,
                      Timestamp.from(clock.instant()),
                      snapshot.account(),
                      snapshot.version()
                    }
                    : new Object[] {verifier, snapshot.account(), snapshot.version()})
            .update();
    if (changed != SINGLE_ACCOUNT) {
      throw new AuthenticationPreconditionException();
    }
  }

  private Snapshot read(UUID account, boolean lock) {
    return jdbc.sql(
            "SELECT account_id, canonical_email, security_version, password_verifier "
                + "FROM authentication_account WHERE account_id = ?"
                + (lock ? " FOR UPDATE" : ""))
        .param(account)
        .query(
            (row, number) ->
                new Snapshot(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    row.getLong(3),
                    row.getString(4)))
        .optional()
        .orElseThrow(AuthenticationPreconditionException::new);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Account mutation requires a writable transaction.");
    }
  }

  /** Transient preparation input; never session authority or diagnostic material. */
  public record Snapshot(UUID account, String canonical, long version, String verifier) {
    @Override
    public String toString() {
      return "AccountActionSnapshot[redacted]";
    }
  }
}
