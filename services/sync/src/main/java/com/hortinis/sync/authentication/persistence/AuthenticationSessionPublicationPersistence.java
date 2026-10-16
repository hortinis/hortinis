package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
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
  private static final String RECOVERY_STATE = "recovery";
  private static final String PASSWORD_STATE = "password";
  private static final String FULL_STATE = "full";
  private static final String ANONYMOUS = "anonymous";
  private static final int MAX_FULL = 5;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final AuthenticationNativeSessions nativeSessions;

  /** All publications use the application datasource and trusted clock. */
  public AuthenticationSessionPublicationPersistence(
      JdbcClient jdbc, Clock clock, AuthenticationNativeSessions nativeSessions) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.nativeSessions = nativeSessions;
  }

  /**
   * Installation admission first, then current account, then current parent; all held to commit.
   */
  public AuthenticationSession lock(AuthenticationSessionTransport.Observation observation) {
    return lock(observation, null);
  }

  /** Account adoption also locks its intended account before the anonymous parent. */
  public AuthenticationSession lock(
      AuthenticationSessionTransport.Observation observation, UUID targetAccount) {
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
    AuthenticationSession before = find(observation, false);
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
    AuthenticationSession parent = find(observation, true);
    if (!Objects.equals(before.account(), parent.account())
        || before.epoch() != parent.epoch()
        || !current(parent, clock.instant())) {
      throw new AuthenticationFailedException();
    }
    return parent;
  }

  /** Non-authorizing read for factor verification; publication always locks and rechecks later. */
  public AuthenticationSession observe(AuthenticationSessionTransport.Observation observation) {
    return find(observation, false);
  }

  /** Rotates an anonymous parent into a ticket-bound, password-hash-only setup ceremony. */
  public UUID stageSetup(
      AuthenticationSession parent,
      SetupMaterial staged,
      AuthenticationSessionTransport transport) {
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
    Replacement replacement = rotate(transport);
    transport.stage(
        new AuthenticationSession(
            replacement.reference(),
            null,
            0,
            "setup",
            retry ? parent.createdAt() : now,
            expires,
            null,
            null,
            staged.login(),
            staged.passwordVerifier(),
            staged.userHandle(),
            replacement.logoutTarget(),
            null,
            null));
    return replacement.reference();
  }

  /** Creates a bounded password-only ceremony before normal or step-up passkey proof. */
  public UUID password(
      AuthenticationSession parent,
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
    Instant expires = now.plus(AuthenticationLifetime.LIMITED_ABSOLUTE);
    Replacement replacement = rotate(transport);
    transport.stage(
        new AuthenticationSession(
            replacement.reference(),
            account.account(),
            account.epoch(),
            PASSWORD_STATE,
            now,
            expires,
            stepUp ? now : null,
            null,
            null,
            null,
            null,
            replacement.logoutTarget(),
            null,
            null));
    retire(parent);
    return replacement.reference();
  }

  /** Full authority follows both factors and cannot silently evict an existing current session. */
  public UUID full(AuthenticationSession parent, AuthenticationSessionTransport transport) {
    requireState(parent, PASSWORD_STATE);
    Instant now = clock.instant();
    requireCurrent(parent, now);
    if (nativeSessions.list(parent.account(), parent.epoch()).size() >= MAX_FULL) {
      throw new AuthenticationOverloadedException();
    }
    Replacement replacement = rotate(transport);
    retire(parent);
    transport.authenticate(parent.account(), parent.epoch(), now, replacement.logoutTarget());
    return replacement.reference();
  }

  /** Target-bound operator staging preserves the parent deadline and existing account handle. */
  public UUID stageOperator(
      AuthenticationSession parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String hash,
      Instant ticketExpiry,
      AuthenticationSessionTransport transport) {
    requireTransaction();
    boolean retry =
        RECOVERY_STATE.equals(parent.state()) && "operator".equals(parent.recoveryKind());
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
    Replacement replacement = rotate(transport);
    transport.stage(
        new AuthenticationSession(
            replacement.reference(),
            account.account(),
            account.epoch(),
            RECOVERY_STATE,
            parent.createdAt(),
            expires,
            null,
            null,
            null,
            hash,
            account.userHandle(),
            replacement.logoutTarget(),
            "operator",
            null));
    return replacement.reference();
  }

  /** Pending password recovery has no account lookup or code validation at issuance. */
  public UUID pendingRecovery(
      AuthenticationSession parent, String login, AuthenticationSessionTransport transport) {
    requireState(parent, ANONYMOUS);
    return recovery(parent, null, PASSWORD_STATE, login, false, transport);
  }

  /** Two independent proofs grant only a fixed-deadline replacement ceremony. */
  public UUID readyRecovery(
      AuthenticationSession parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String kind,
      AuthenticationSessionTransport transport) {
    boolean passwordProof =
        RECOVERY_STATE.equals(parent.state())
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
      AuthenticationSession parent,
      AuthenticationAccountPersistence.AccountSnapshot account,
      String kind,
      String requestedLogin,
      boolean proved,
      AuthenticationSessionTransport transport) {
    requireTransaction();
    Instant now = clock.instant();
    requireCurrent(parent, now);
    Replacement replacement = rotate(transport);
    transport.stage(
        new AuthenticationSession(
            replacement.reference(),
            account == null ? null : account.account(),
            account == null ? 0 : account.epoch(),
            RECOVERY_STATE,
            parent.createdAt(),
            parent.expiresAt(),
            null,
            proved ? now : null,
            requestedLogin,
            null,
            null,
            replacement.logoutTarget(),
            kind,
            null));
    retire(parent);
    return replacement.reference();
  }

  /** Recovery proof never substitutes for recent full normal authentication. */
  public void requireRecovery(AuthenticationSession parent, String kind) {
    requireState(parent, RECOVERY_STATE);
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
      AuthenticationSession parent, byte[] credentialId, AuthenticationSessionTransport transport) {
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
    Replacement replacement = rotate(transport);
    transport.stage(
        new AuthenticationSession(
            replacement.reference(),
            parent.account(),
            parent.epoch(),
            RECOVERY_STATE,
            parent.createdAt(),
            parent.expiresAt(),
            null,
            parent.bothFactorsAt(),
            null,
            null,
            null,
            replacement.logoutTarget(),
            "passkey",
            credentialId));
    retire(parent);
    return replacement.reference();
  }

  /** Removes the predecessor only inside the caller's publication transaction. */
  public void retire(AuthenticationSession parent) {
    requireTransaction();
    jdbc.sql("DELETE FROM authentication_challenge WHERE session_reference = ?")
        .param(parent.reference())
        .update();
    jdbc.sql("DELETE FROM authentication_operator_ticket WHERE claimed_session_reference = ?")
        .param(parent.reference())
        .update();
  }

  /** Checks a trusted locked parent state within the current writable transaction. */
  public static void requireState(AuthenticationSession parent, String state) {
    requireTransaction();
    if (!state.equals(parent.state())) {
      throw new AuthenticationFailedException();
    }
  }

  /** Current full authority is required even when recent factors are not required. */
  public void requireFull(AuthenticationSession parent) {
    requireState(parent, FULL_STATE);
    requireCurrent(parent, clock.instant());
  }

  /** Privileged factor/session changes require recent normal factors, never recovery proof. */
  public void requireRecent(AuthenticationSession parent) {
    requireFull(parent);
    if (!AuthenticationLifetime.recentBothFactors(parent.bothFactorsAt(), clock.instant())) {
      throw new AuthenticationPreconditionException();
    }
  }

  /** Logout must be able to remove expired/revoked rows without granting them current authority. */
  public Optional<AuthenticationSession> lockForLogout(
      AuthenticationSessionTransport.Observation observation) {
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
  public boolean currentFull(AuthenticationSession parent) {
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

  private AuthenticationSession find(
      AuthenticationSessionTransport.Observation observation, boolean lock) {
    return findOptional(observation, lock).orElseThrow(AuthenticationFailedException::new);
  }

  private Optional<AuthenticationSession> findOptional(
      AuthenticationSessionTransport.Observation observation, boolean lock) {
    if (observation == null || observation.reference() == null) {
      throw new AuthenticationFailedException();
    }
    return nativeSessions.find(
        observation.reference().value(),
        observation.verifier(),
        lock ? AuthenticationNativeSessions.Lock.UPDATE : AuthenticationNativeSessions.Lock.NONE);
  }

  private static void requireCurrent(AuthenticationSession parent, Instant now) {
    if (!current(parent, now)) {
      throw new AuthenticationFailedException();
    }
  }

  private static boolean current(AuthenticationSession parent, Instant now) {
    return parent.isUnexpired(now)
        && (!FULL_STATE.equals(parent.state()) || parent.hasCurrentFactors(now));
  }

  private static Replacement rotate(AuthenticationSessionTransport transport) {
    UUID reference = UUID.randomUUID();
    var observed = transport.rotate(new SessionAuthorityReference(reference));
    if (!reference.equals(observed.reference().value())
        || !observed.verifier().matches("[a-f0-9]{64}")) {
      throw new AuthenticationFailedException();
    }
    return new Replacement(reference, observed.verifier(), AuthenticationSecrets.randomValue());
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session publication requires a writable transaction.");
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
