package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Ordered current-ceremony locks and atomic fresh-reference publication with native transport. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionPublicationPersistence {
  private static final String OWNED_AUTHORITY_COLUMNS =
      "logout_target, state, account_id, authority_epoch, created_at, expires_at, ";
  private static final String INSERT_AUTHORITY =
      "INSERT INTO authentication_session_authority (session_reference, session_verifier, ";
  private static final String PASSWORD_STATE = "password";
  private static final String FULL_STATE = "full";
  private static final String ANONYMOUS = "anonymous";
  private static final int MAX_LIMITED = 1000;
  private static final int MAX_FULL = 5;
  private static final int ONE_ROW = 1;
  private final JdbcClient jdbc;
  private final Clock clock;

  /** All publications use the application datasource and trusted clock. */
  public AuthenticationSessionPublicationPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Installation admission first, then current account, then current parent; all held to commit.
   */
  public Parent lock(AuthenticationSessionTransport.Observation observation) {
    return lock(observation, null);
  }

  /** Account adoption also locks its intended account before the anonymous parent. */
  public Parent lock(AuthenticationSessionTransport.Observation observation, UUID targetAccount) {
    requireTransaction();
    if (observation == null
        || observation.reference() == null
        || observation.verifier() == null
        || !observation.verifier().matches("[a-f0-9]{64}")) {
      throw new AuthenticationFailedException();
    }
    UUID installation =
        jdbc.sql(
                "SELECT server_instance_id FROM technical_server_installation "
                    + "WHERE singleton AND access_mode = 'single-user-auth' FOR UPDATE")
            .query(UUID.class)
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    Parent before = find(observation, false);
    if (targetAccount != null
        && before.account() != null
        && !targetAccount.equals(before.account())) {
      throw new AuthenticationFailedException();
    }
    UUID lockingAccount = before.account() == null ? targetAccount : before.account();
    if (lockingAccount != null) {
      boolean current =
          jdbc.sql(
                  "SELECT (? = 0 OR authority_epoch = ?) AND status = 'active' "
                      + "AND server_instance_id = ? FROM authentication_account "
                      + "WHERE account_id = ? FOR UPDATE")
              .params(before.epoch(), before.epoch(), installation, lockingAccount)
              .query(Boolean.class)
              .optional()
              .orElse(false);
      if (!current) {
        throw new AuthenticationFailedException();
      }
    }
    Parent parent = find(observation, true);
    if (!Objects.equals(before.account(), parent.account())
        || before.epoch() != parent.epoch()
        || !current(parent, clock.instant())) {
      throw new AuthenticationFailedException();
    }
    return parent;
  }

  /** Non-authorizing read for factor verification; publication always locks and rechecks later. */
  public Parent observe(AuthenticationSessionTransport.Observation observation) {
    return find(observation, false);
  }

  /** Rotates an anonymous parent into a ticket-bound, password-hash-only setup ceremony. */
  public UUID stageSetup(
      Parent parent, SetupMaterial staged, AuthenticationSessionTransport transport) {
    requireTransaction();
    boolean retry = "setup".equals(parent.state());
    if (!retry && !ANONYMOUS.equals(parent.state())) {
      throw new AuthenticationFailedException();
    }
    Instant now = clock.instant();
    requireCurrent(parent, now);
    Instant expires =
        earlier(now.plus(AuthenticationLifetime.LIMITED_ABSOLUTE), staged.ticketExpiry());
    if (retry) {
      expires = earlier(expires, parent.expiresAt());
    }
    if (!now.isBefore(expires)) {
      throw new AuthenticationFailedException();
    }
    Replacement replacement = rotate(transport, AuthenticationLifetime.LIMITED_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + "logout_target, state, created_at, expires_at, staged_login_identifier, "
                + "staged_password_verifier, staged_user_handle) VALUES (?, ?, ?, "
                + "'setup', ?, ?, ?, ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            Timestamp.from(retry ? parent.createdAt() : now),
            Timestamp.from(expires),
            staged.login(),
            staged.passwordVerifier(),
            staged.userHandle())
        .update();
    return replacement.reference();
  }

  /** Creates password-only authority; step-up preserves the predecessor's original full login. */
  public UUID password(
      Parent parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      AuthenticationSessionTransport transport,
      boolean stepUp) {
    requireState(parent, stepUp ? FULL_STATE : ANONYMOUS);
    if (stepUp
        && (!account.account().equals(parent.account()) || account.epoch() != parent.epoch())) {
      throw new AuthenticationFailedException();
    }
    Instant now = clock.instant();
    requireCurrent(parent, now);
    if (stepUp) {
      requireLimitedSlot(now);
    }
    Instant expires = now.plus(AuthenticationLifetime.LIMITED_ABSOLUTE);
    if (stepUp) {
      expires = earlier(expires, parent.loginAt().plus(AuthenticationLifetime.FULL_ABSOLUTE));
    }
    if (!now.isBefore(expires)) {
      throw new AuthenticationFailedException();
    }
    Replacement replacement = rotate(transport, AuthenticationLifetime.LIMITED_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + OWNED_AUTHORITY_COLUMNS
                + "full_login_at, last_interaction_at) VALUES (?, ?, ?, 'password', "
                + "?, ?, ?, ?, ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            account.account(),
            account.epoch(),
            Timestamp.from(now),
            Timestamp.from(expires),
            timestamp(stepUp ? parent.loginAt() : null),
            timestamp(stepUp ? parent.interactionAt() : null))
        .update();
    retire(parent);
    return replacement.reference();
  }

  /** Full authority follows both factors and cannot silently evict an existing current session. */
  public UUID full(Parent parent, AuthenticationSessionTransport transport) {
    requireState(parent, PASSWORD_STATE);
    Instant now = clock.instant();
    requireCurrent(parent, now);
    int active =
        jdbc.sql(
                "SELECT count(*) FROM authentication_session_authority "
                    + "WHERE account_id = ? AND authority_epoch = ? AND state = 'full' "
                    + "AND created_at <= ? AND expires_at > ? AND full_login_at <= ? "
                    + "AND full_login_at > ? AND last_interaction_at <= ? AND "
                    + "last_interaction_at > ?")
            .params(
                parent.account(),
                parent.epoch(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.minus(AuthenticationLifetime.FULL_ABSOLUTE)),
                Timestamp.from(now),
                Timestamp.from(now.minus(AuthenticationLifetime.FULL_IDLE)))
            .query(Integer.class)
            .single();
    if (active >= MAX_FULL) {
      throw new AuthenticationOverloadedException();
    }
    Instant login = parent.loginAt() == null ? now : parent.loginAt();
    Replacement replacement = rotate(transport, AuthenticationLifetime.FULL_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + OWNED_AUTHORITY_COLUMNS
                + "full_login_at, last_interaction_at, both_factors_at) "
                + "VALUES (?, ?, ?, 'full', ?, ?, ?, ?, ?, ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            parent.account(),
            parent.epoch(),
            Timestamp.from(now),
            Timestamp.from(login.plus(AuthenticationLifetime.FULL_ABSOLUTE)),
            Timestamp.from(login),
            Timestamp.from(now),
            Timestamp.from(now))
        .update();
    retire(parent);
    return replacement.reference();
  }

  /** Target-bound operator staging preserves the parent deadline and existing account handle. */
  public UUID stageOperator(
      Parent parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String hash,
      Instant ticketExpiry,
      AuthenticationSessionTransport transport) {
    requireTransaction();
    boolean retry = "recovery".equals(parent.state()) && "operator".equals(parent.recoveryKind());
    if (!ANONYMOUS.equals(parent.state()) && !retry
        || retry
            && (!account.account().equals(parent.account()) || parent.bothFactorsAt() != null)) {
      throw new AuthenticationFailedException();
    }
    requireCurrent(parent, clock.instant());
    Instant expires = earlier(parent.expiresAt(), ticketExpiry);
    if (!clock.instant().isBefore(expires)) {
      throw new AuthenticationFailedException();
    }
    Replacement replacement = rotate(transport, AuthenticationLifetime.LIMITED_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + OWNED_AUTHORITY_COLUMNS
                + "recovery_kind, staged_password_verifier, staged_user_handle) "
                + "VALUES (?, ?, ?, 'recovery', ?, ?, ?, ?, 'operator', ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            account.account(),
            account.epoch(),
            Timestamp.from(parent.createdAt()),
            Timestamp.from(expires),
            hash,
            account.userHandle())
        .update();
    return replacement.reference();
  }

  /** Pending password recovery has no account lookup or code validation at issuance. */
  public UUID pendingRecovery(
      Parent parent, String login, AuthenticationSessionTransport transport) {
    requireState(parent, ANONYMOUS);
    return recovery(parent, null, PASSWORD_STATE, login, false, transport);
  }

  /** Two independent proofs grant only a fixed-deadline replacement ceremony. */
  public UUID readyRecovery(
      Parent parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String kind,
      AuthenticationSessionTransport transport) {
    boolean passwordProof =
        "recovery".equals(parent.state())
            && PASSWORD_STATE.equals(parent.recoveryKind())
            && parent.bothFactorsAt() == null
            && parent.account() == null;
    boolean passkeyProof = ANONYMOUS.equals(parent.state()) && "passkey".equals(kind);
    if (!(passwordProof && PASSWORD_STATE.equals(kind)) && !passkeyProof) {
      throw new AuthenticationFailedException();
    }
    return recovery(parent, account, kind, null, true, transport);
  }

  private UUID recovery(
      Parent parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String kind,
      String requestedLogin,
      boolean proved,
      AuthenticationSessionTransport transport) {
    requireTransaction();
    Instant now = clock.instant();
    requireCurrent(parent, now);
    Replacement replacement = rotate(transport, AuthenticationLifetime.LIMITED_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + OWNED_AUTHORITY_COLUMNS
                + "recovery_kind, staged_login_identifier, both_factors_at) "
                + "VALUES (?, ?, ?, 'recovery', ?, ?, ?, ?, ?, ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            account == null ? null : account.account(),
            account == null ? null : account.epoch(),
            Timestamp.from(parent.createdAt()),
            Timestamp.from(parent.expiresAt()),
            kind,
            requestedLogin,
            timestamp(proved ? now : null))
        .update();
    retire(parent);
    return replacement.reference();
  }

  /** Recovery proof never substitutes for recent full normal authentication. */
  public void requireRecovery(Parent parent, String kind) {
    requireState(parent, "recovery");
    requireCurrent(parent, clock.instant());
    if (!kind.equals(parent.recoveryKind())
        || parent.account() == null
        || parent.bothFactorsAt() == null
        || clock.instant().isBefore(parent.bothFactorsAt())) {
      throw new AuthenticationFailedException();
    }
  }

  /** Selecting a current owned lost key replaces only the nonce/target, never the deadline. */
  public UUID replacement(
      Parent parent, byte[] credentialId, AuthenticationSessionTransport transport) {
    requireRecovery(parent, "passkey");
    boolean owned =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM authentication_credential "
                    + "WHERE account_id = ? AND credential_id = ?)")
            .params(parent.account(), credentialId.clone())
            .query(Boolean.class)
            .single();
    if (!owned) {
      throw new AuthenticationFailedException();
    }
    Replacement replacement = rotate(transport, AuthenticationLifetime.LIMITED_ABSOLUTE);
    jdbc.sql(
            INSERT_AUTHORITY
                + OWNED_AUTHORITY_COLUMNS
                + "recovery_kind, both_factors_at, replacement_credential_id) "
                + "VALUES (?, ?, ?, 'recovery', ?, ?, ?, ?, 'passkey', ?, ?)")
        .params(
            replacement.reference(),
            replacement.verifier(),
            replacement.logoutTarget(),
            parent.account(),
            parent.epoch(),
            Timestamp.from(parent.createdAt()),
            Timestamp.from(parent.expiresAt()),
            Timestamp.from(parent.bothFactorsAt()),
            credentialId.clone())
        .update();
    retire(parent);
    return replacement.reference();
  }

  /** Removes the predecessor only inside the caller's publication transaction. */
  public void retire(Parent parent) {
    requireTransaction();
    if (jdbc.sql("DELETE FROM authentication_session_authority WHERE session_reference = ?")
            .param(parent.reference())
            .update()
        != ONE_ROW) {
      throw new AuthenticationFailedException();
    }
  }

  /** Checks a trusted locked parent state within the current writable transaction. */
  public static void requireState(Parent parent, String state) {
    requireTransaction();
    if (!state.equals(parent.state())) {
      throw new AuthenticationFailedException();
    }
  }

  /** Current full authority is required even when recent factors are not required. */
  public void requireFull(Parent parent) {
    requireState(parent, FULL_STATE);
    requireCurrent(parent, clock.instant());
  }

  /** Privileged factor/session changes require recent normal factors, never recovery proof. */
  public void requireRecent(Parent parent) {
    requireFull(parent);
    if (!AuthenticationLifetime.recentBothFactors(parent.bothFactorsAt(), clock.instant())) {
      throw new AuthenticationPreconditionException();
    }
  }

  /** Logout must be able to remove expired/revoked rows without granting them current authority. */
  public Optional<Parent> lockForLogout(AuthenticationSessionTransport.Observation observation) {
    requireTransaction();
    jdbc.sql(
            "SELECT server_instance_id FROM technical_server_installation "
                + "WHERE singleton AND access_mode = 'single-user-auth' FOR UPDATE")
        .query(UUID.class)
        .optional()
        .orElseThrow(AuthenticationFailedException::new);
    if (observation == null) {
      return Optional.empty();
    }
    var preliminary = findOptional(observation, false);
    if (preliminary.isEmpty()) {
      return Optional.empty();
    }
    var before = preliminary.orElseThrow();
    if (before.account() != null) {
      jdbc.sql("SELECT account_id FROM authentication_account WHERE account_id = ? FOR UPDATE")
          .param(before.account())
          .query(UUID.class)
          .optional();
    }
    return findOptional(observation, true)
        .filter(parent -> Objects.equals(parent.account(), before.account()));
  }

  /**
   * Checks whether logout currently targets full authority while the account/parent locks are held.
   */
  public boolean currentFull(Parent parent) {
    requireTransaction();
    if (!FULL_STATE.equals(parent.state()) || !current(parent, clock.instant())) {
      return false;
    }
    return jdbc.sql(
            "SELECT a.status = 'active' AND a.authority_epoch = ? "
                + "FROM authentication_account a JOIN technical_server_installation i "
                + "ON i.server_instance_id = a.server_instance_id "
                + "WHERE a.account_id = ? AND i.access_mode = 'single-user-auth'")
        .params(parent.epoch(), parent.account())
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  private Parent find(AuthenticationSessionTransport.Observation observation, boolean lock) {
    return findOptional(observation, lock).orElseThrow(AuthenticationFailedException::new);
  }

  private Optional<Parent> findOptional(
      AuthenticationSessionTransport.Observation observation, boolean lock) {
    if (observation == null || observation.reference() == null) {
      throw new AuthenticationFailedException();
    }
    return jdbc.sql(
            "SELECT session_reference, account_id, authority_epoch, state, created_at, "
                + "expires_at, full_login_at, last_interaction_at, both_factors_at, "
                + "staged_login_identifier, staged_password_verifier, "
                + "staged_user_handle, logout_target, recovery_kind, replacement_credential_id "
                + "FROM authentication_session_authority WHERE session_reference = ? "
                + "AND session_verifier = ? "
                + (lock ? "FOR UPDATE" : ""))
        .params(observation.reference().value(), observation.verifier())
        .query(
            (row, number) ->
                new Parent(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getLong(3),
                    row.getString(4),
                    row.getTimestamp(5).toInstant(),
                    row.getTimestamp(6).toInstant(),
                    instant(row, 7),
                    instant(row, 8),
                    instant(row, 9),
                    row.getString(10),
                    row.getString(11),
                    row.getBytes(12),
                    row.getString(13),
                    row.getString(14),
                    row.getBytes(15)))
        .optional();
  }

  private static void requireCurrent(Parent parent, Instant now) {
    if (!current(parent, now)) {
      throw new AuthenticationFailedException();
    }
  }

  private static boolean current(Parent parent, Instant now) {
    return !now.isBefore(parent.createdAt())
        && now.isBefore(parent.expiresAt())
        && (parent.loginAt() == null
            || !now.isBefore(parent.loginAt())
                && now.isBefore(parent.loginAt().plus(AuthenticationLifetime.FULL_ABSOLUTE)))
        && (!FULL_STATE.equals(parent.state())
            || parent.bothFactorsAt() != null
                && !now.isBefore(parent.bothFactorsAt())
                && !parent.bothFactorsAt().isBefore(parent.loginAt())
                && AuthenticationLifetime.fullIsCurrent(
                    parent.loginAt(), parent.interactionAt(), now));
  }

  private void requireLimitedSlot(Instant now) {
    int count =
        jdbc.sql(
                "SELECT count(*) FROM authentication_session_authority "
                    + "WHERE state <> 'full' AND expires_at > ?")
            .param(Timestamp.from(now))
            .query(Integer.class)
            .single();
    if (count >= MAX_LIMITED) {
      throw new AuthenticationOverloadedException();
    }
  }

  private static Replacement rotate(
      AuthenticationSessionTransport transport, java.time.Duration lifetime) {
    UUID reference = UUID.randomUUID();
    var observed = transport.rotate(new SessionAuthorityReference(reference), lifetime);
    if (!reference.equals(observed.reference().value())
        || !observed.verifier().matches("[a-f0-9]{64}")) {
      throw new AuthenticationFailedException();
    }
    return new Replacement(reference, observed.verifier(), AuthenticationSecrets.randomValue());
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static Instant instant(ResultSet row, int index) throws SQLException {
    java.time.OffsetDateTime value = row.getObject(index, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session publication requires a writable transaction.");
    }
  }

  /** Internal current parent, never serialized into native attributes, HTTP or diagnostics. */
  public record Parent(
      UUID reference,
      UUID account,
      long epoch,
      String state,
      Instant createdAt,
      Instant expiresAt,
      Instant loginAt,
      Instant interactionAt,
      Instant bothFactorsAt,
      String stagedLogin,
      String stagedPassword,
      byte[] stagedHandle,
      String logoutTarget,
      String recoveryKind,
      byte[] replacementCredential) {
    /** Copies staged bytes and keeps sensitive material out of diagnostics. */
    public Parent {
      if (replacementCredential != null) {
        replacementCredential = replacementCredential.clone();
      }
      if (stagedHandle != null) {
        stagedHandle = stagedHandle.clone();
      }
    }

    @Override
    public byte[] replacementCredential() {
      return replacementCredential == null ? null : replacementCredential.clone();
    }

    @Override
    public byte[] stagedHandle() {
      return stagedHandle == null ? null : stagedHandle.clone();
    }

    @Override
    public String toString() {
      return "AuthenticationParent[redacted]";
    }
  }

  /** Trusted setup material from policy validation and the locked host ticket. */
  public record SetupMaterial(
      String login, String passwordVerifier, byte[] userHandle, Instant ticketExpiry) {
    /** Retains no caller-mutable handle. */
    public SetupMaterial {
      userHandle = userHandle.clone();
    }

    @Override
    public byte[] userHandle() {
      return userHandle.clone();
    }

    @Override
    public String toString() {
      return "SetupMaterial[redacted]";
    }
  }

  private record Replacement(UUID reference, String verifier, String logoutTarget) {
    @Override
    public String toString() {
      return "SessionReplacement[redacted]";
    }
  }
}
