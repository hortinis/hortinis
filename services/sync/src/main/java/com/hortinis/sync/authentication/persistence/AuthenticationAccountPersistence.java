package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationCredential;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Minimized account/key snapshots and locked current-state publication, never password
 * verification.
 */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationAccountPersistence {
  private static final int MAX_CREDENTIALS = 8;
  private static final int MIN_CREDENTIALS = 1;
  private static final String ACCOUNT_COLUMNS =
      "a.account_id, a.authority_epoch, a.server_instance_id, a.synchronization_scope_id, "
          + "a.login_identifier, a.password_verifier, a.user_handle";
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Uses the application's trusted clock; crypto is outside this persistence component. */
  public AuthenticationAccountPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Missing/disabled/inconsistent sole-account installations return no verification snapshot. */
  public Optional<AccountSnapshot> findLogin(String canonicalLogin) {
    return queryAccount("a.login_identifier = ?", canonicalLogin, false);
  }

  /** Trusted account selectors come from a server-owned parent, never from a request. */
  public AccountSnapshot findAccount(UUID account) {
    return queryAccount("a.account_id = ?", account, false)
        .orElseThrow(AuthenticationFailedException::new);
  }

  /**
   * Locks and compares every password-verification snapshot field before granting a partial state.
   */
  public AccountSnapshot requireUnchanged(AccountSnapshot observed) {
    requireTransaction();
    AccountSnapshot current =
        queryAccount("a.account_id = ?", observed.account(), true)
            .orElseThrow(AuthenticationFailedException::new);
    if (current.epoch() != observed.epoch()
        || !current.instance().equals(observed.instance())
        || !current.scope().equals(observed.scope())
        || !current.login().equals(observed.login())
        || !current.passwordVerifier().equals(observed.passwordVerifier())
        || !MessageDigest.isEqual(current.userHandle(), observed.userHandle())) {
      throw new AuthenticationFailedException();
    }
    return current;
  }

  /** Bounded public-key-only credential snapshots for the replaceable verification adapter. */
  public List<AuthenticationCredential> credentials(UUID account) {
    List<AuthenticationCredential> result =
        jdbc.sql(
                "SELECT credential_id, public_key_cose, algorithm, "
                    + "signature_count, backup_eligible, backed_up, label FROM "
                    + "authentication_credential "
                    + "WHERE account_id = ? ORDER BY credential_id LIMIT 9")
            .param(account)
            .query(
                (row, number) ->
                    new AuthenticationCredential(
                        row.getBytes(1),
                        row.getBytes(2),
                        row.getInt(3),
                        row.getLong(4),
                        row.getBoolean(5),
                        row.getBoolean(6),
                        row.getString(7)))
            .list();
    if (result.isEmpty() || result.size() > 8) {
      throw new AuthenticationFailedException();
    }
    return result;
  }

  /** Adds only the first verified registration while setup owns its new account transaction. */
  public void firstCredential(UUID account, BoundWebAuthnOperations.VerifiedCredential proof) {
    requireTransaction();
    if (proof.kind() != BoundWebAuthnOperations.ProofKind.REGISTRATION
        || jdbc.sql("SELECT count(*) FROM authentication_credential WHERE account_id = ?")
                .param(account)
                .query(Integer.class)
                .single()
            != 0) {
      throw new AuthenticationFailedException();
    }
    insertCredential(account, proof);
  }

  /** Enforces the final eight-key ceiling under the current account's exclusive lock. */
  public void additionalCredential(
      AccountSnapshot observed, BoundWebAuthnOperations.VerifiedCredential proof) {
    var current = requireUnchanged(observed);
    if (credentialCount(current.account()) >= MAX_CREDENTIALS) {
      throw new AuthenticationPreconditionException();
    }
    insertCredential(current.account(), proof);
  }

  /** Owned removal never deletes the last verified usable credential. */
  public void revokeCredential(AccountSnapshot observed, byte[] credentialId) {
    var current = requireUnchanged(observed);
    if (credentialCount(current.account()) <= MIN_CREDENTIALS) {
      throw new AuthenticationPreconditionException();
    }
    boolean removed =
        jdbc.sql("DELETE FROM authentication_credential WHERE account_id = ? AND credential_id = ?")
                .params(current.account(), credentialId.clone())
                .update()
            == 1;
    if (!removed) {
      throw new AuthenticationPreconditionException();
    }
  }

  /**
   * Insert the verified replacement before removing an owned lost key, even at the eight-key cap.
   */
  public void replaceCredential(
      AccountSnapshot observed,
      byte[] lostCredential,
      BoundWebAuthnOperations.VerifiedCredential proof) {
    var current = requireUnchanged(observed);
    if (lostCredential == null
        || MessageDigest.isEqual(lostCredential, proof.credential().id())
        || credentialCount(current.account()) > MAX_CREDENTIALS
        || !jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM authentication_credential "
                    + "WHERE account_id = ? AND credential_id = ?)")
            .params(current.account(), lostCredential.clone())
            .query(Boolean.class)
            .single()) {
      throw new AuthenticationFailedException();
    }
    insertCredential(current.account(), proof);
    if (jdbc.sql("DELETE FROM authentication_credential WHERE account_id = ? AND credential_id = ?")
            .params(current.account(), lostCredential.clone())
            .update()
        != MIN_CREDENTIALS) {
      throw new AuthenticationFailedException();
    }
  }

  /** Controlled operator recovery verifies the new key before removing all obsolete keys. */
  public void replaceAllCredentials(
      AccountSnapshot observed, BoundWebAuthnOperations.VerifiedCredential proof) {
    var current = requireUnchanged(observed);
    insertCredential(current.account(), proof);
    jdbc.sql("DELETE FROM authentication_credential WHERE account_id = ? AND credential_id <> ?")
        .params(current.account(), proof.credential().id())
        .update();
  }

  private int credentialCount(UUID account) {
    return jdbc.sql("SELECT count(*) FROM authentication_credential WHERE account_id = ?")
        .param(account)
        .query(Integer.class)
        .single();
  }

  private void insertCredential(UUID account, BoundWebAuthnOperations.VerifiedCredential proof) {
    requireTransaction();
    if (proof.kind() != BoundWebAuthnOperations.ProofKind.REGISTRATION) {
      throw new AuthenticationFailedException();
    }
    var credential = proof.credential();
    byte[] handle =
        jdbc.sql(
                "SELECT user_handle FROM authentication_account WHERE account_id "
                    + "= ? FOR UPDATE")
            .param(account)
            .query((row, number) -> row.getBytes(1))
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    if (!MessageDigest.isEqual(handle, proof.userHandle())) {
      throw new AuthenticationFailedException();
    }
    boolean inserted =
        jdbc.sql(
                    "INSERT INTO authentication_credential (credential_id, "
                        + "account_id, public_key_cose, "
                        + "algorithm, signature_count, backup_eligible, backed_up, label) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (credential_id) DO NOTHING")
                .params(
                    credential.id(),
                    account,
                    credential.publicKey(),
                    proof.algorithm(),
                    credential.counter(),
                    credential.backupEligible(),
                    credential.backedUp(),
                    credential.label())
                .update()
            == 1;
    if (!inserted) {
      throw new AuthenticationPreconditionException();
    }
  }

  /**
   * Rechecks current ownership/key/counter after crypto; concurrent old snapshots cannot overwrite.
   */
  public void publishAssertion(
      AccountSnapshot account, BoundWebAuthnOperations.VerifiedCredential proof) {
    requireTransaction();
    if (proof.kind() != BoundWebAuthnOperations.ProofKind.ASSERTION) {
      throw new AuthenticationFailedException();
    }
    var verified = proof.credential();
    AuthenticationCredential current =
        jdbc.sql(
                "SELECT credential_id, public_key_cose, algorithm, "
                    + "signature_count, backup_eligible, backed_up, label FROM "
                    + "authentication_credential "
                    + "WHERE account_id = ? AND credential_id = ? FOR UPDATE")
            .params(account.account(), verified.id())
            .query(
                (row, number) ->
                    new AuthenticationCredential(
                        row.getBytes(1),
                        row.getBytes(2),
                        row.getInt(3),
                        row.getLong(4),
                        row.getBoolean(5),
                        row.getBoolean(6),
                        row.getString(7)))
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    long counter = verified.counter();
    boolean eligible = Boolean.TRUE.equals(verified.backupEligible());
    if (!MessageDigest.isEqual(account.userHandle(), proof.userHandle())
        || !MessageDigest.isEqual(current.publicKey(), verified.publicKey())
        || current.algorithm() != proof.algorithm()
        || current.backupEligible() != eligible
        || !eligible && !(counter == 0 && current.counter() == 0) && counter <= current.counter()) {
      throw new AuthenticationFailedException();
    }
    jdbc.sql(
            "UPDATE authentication_credential SET signature_count = ?, backed_up = ? "
                + "WHERE account_id = ? AND credential_id = ?")
        .params(
            Math.max(counter, current.counter()),
            verified.backedUp(),
            account.account(),
            current.id())
        .update();
  }

  /**
   * Activation is called only after the setup component locks and verifies sole empty ownership.
   */
  public UUID activate(UUID installation, UUID scope, AuthenticationSession parent) {
    AuthenticationSessionPublicationPersistence.requireState(parent, "setup");
    UUID account = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO authentication_account (account_id, "
                + "server_instance_id, synchronization_scope_id, "
                + "login_identifier, status, password_verifier, user_handle, created_at) "
                + "VALUES (?, ?, ?, ?, 'active', ?, ?, ?)")
        .params(
            account,
            installation,
            scope,
            parent.stagedLogin(),
            parent.stagedPassword(),
            parent.stagedHandle(),
            Timestamp.from(clock.instant()))
        .update();
    return account;
  }

  private Optional<AccountSnapshot> queryAccount(String predicate, Object value, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + ACCOUNT_COLUMNS
                + " FROM authentication_account a "
                + "JOIN technical_server_installation i ON i.server_instance_id = "
                + "a.server_instance_id "
                + "JOIN technical_synchronization_scope s ON "
                + "s.synchronization_scope_id = a.synchronization_scope_id "
                + "AND s.server_instance_id = a.server_instance_id AND s.technical_default "
                + "WHERE a.status = 'active' AND i.access_mode = 'single-user-auth' AND "
                + predicate
                + " AND (SELECT count(*) FROM authentication_account) = 1 "
                + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1 "
                + (lock ? "FOR UPDATE OF a" : ""))
        .param(value)
        .query(
            (row, number) ->
                new AccountSnapshot(
                    row.getObject(1, UUID.class),
                    row.getLong(2),
                    row.getObject(3, UUID.class),
                    row.getObject(4, UUID.class),
                    row.getString(5),
                    row.getString(6),
                    row.getBytes(7)))
        .optional();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Account publication requires a writable transaction.");
    }
  }

  /** Not authority: it must be checked again inside the eventual publication transaction. */
  public record AccountSnapshot(
      UUID account,
      long epoch,
      UUID instance,
      UUID scope,
      String login,
      String passwordVerifier,
      byte[] userHandle) {
    /** Copies the account's opaque WebAuthn handle. */
    public AccountSnapshot {
      userHandle = userHandle.clone();
    }

    @Override
    public byte[] userHandle() {
      return userHandle.clone();
    }

    @Override
    public String toString() {
      return "AccountSnapshot[redacted]";
    }
  }
}
