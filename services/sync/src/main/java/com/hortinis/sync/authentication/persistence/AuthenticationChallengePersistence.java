package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationChallengePurpose;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ordered challenge locks and committed one-use consumption before any cryptographic proof. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationChallengePersistence {
  private static final int MAX_ACCOUNT_CHALLENGES = 8;
  private static final String FULL = "full";
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate consumption;

  /** Uses the application's transaction manager; consumption must commit before proof begins. */
  public AuthenticationChallengePersistence(
      JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.clock = clock;
    consumption = new TransactionTemplate(transactions);
    consumption.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Issues or replaces the current challenge within the trusted ceremony's publication transaction.
   */
  public Instant issue(
      UUID sessionReference,
      AuthenticationChallengePurpose purpose,
      byte[] challenge,
      String recoveryCodeVerifier) {
    requirePublicationTransaction();
    Objects.requireNonNull(purpose);
    if (challenge == null
        || challenge.length != 32
        || (purpose == AuthenticationChallengePurpose.RECOVER_PASSWORD)
            != (recoveryCodeVerifier != null)
        || recoveryCodeVerifier != null && !recoveryCodeVerifier.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid trusted challenge material.");
    }
    LockedParent locked = lockParent(sessionReference, true);
    Instant now = clock.instant();
    if (!selectedAuthenticationMode()
        || !locked.accountCurrent()
        || !permitted(locked.parent(), purpose, now)) {
      throw new AuthenticationFailedException();
    }
    AuthenticationSession parent = locked.parent();
    if (parent.account() != null) {
      int count =
          jdbc.sql(
                  "SELECT count(*) FROM authentication_challenge c "
                      + "JOIN authentication_session_authority s "
                      + "ON s.session_reference = c.session_reference "
                      + "WHERE s.account_id = ? AND c.session_reference <> ? "
                      + "AND c.expires_at > ? AND c.created_at <= ?")
              .params(parent.account(), sessionReference, Timestamp.from(now), Timestamp.from(now))
              .query(Integer.class)
              .single();
      if (count >= MAX_ACCOUNT_CHALLENGES) {
        throw new AuthenticationOverloadedException();
      }
    }
    Instant expires = AuthenticationLifetime.challengeExpiresAt(now, parent.expiresAt());
    jdbc.sql(
            "INSERT INTO authentication_challenge (session_reference, challenge, purpose, "
                + "created_at, expires_at, recovery_code_verifier) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (session_reference) DO UPDATE SET challenge = EXCLUDED.challenge, "
                + "purpose = EXCLUDED.purpose, created_at = EXCLUDED.created_at, "
                + "expires_at = EXCLUDED.expires_at, "
                + "recovery_code_verifier = EXCLUDED.recovery_code_verifier")
        .params(
            sessionReference,
            challenge.clone(),
            purpose.wireValue(),
            Timestamp.from(now),
            Timestamp.from(expires),
            recoveryCodeVerifier)
        .update();
    return expires;
  }

  /**
   * Removes the proof nonce even on expired/wrong-purpose attempts; later failures cannot restore
   * it.
   */
  public ConsumedChallenge consume(UUID sessionReference, AuthenticationChallengePurpose purpose) {
    Objects.requireNonNull(purpose);
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Consume the challenge before a publication transaction.");
    }
    Optional<ConsumedChallenge> result =
        consumption.execute(status -> consumeLocked(sessionReference, purpose));
    return Objects.requireNonNull(result).orElseThrow(AuthenticationFailedException::new);
  }

  private Optional<ConsumedChallenge> consumeLocked(
      UUID sessionReference, AuthenticationChallengePurpose purpose) {
    LockedParent locked = lockParent(sessionReference, false);
    Optional<StoredChallenge> stored =
        jdbc.sql(
                "DELETE FROM authentication_challenge WHERE session_reference = ? "
                    + "RETURNING challenge, purpose, created_at, expires_at, "
                    + "recovery_code_verifier")
            .param(sessionReference)
            .query(
                (row, number) ->
                    new StoredChallenge(
                        row.getBytes(1),
                        row.getString(2),
                        row.getTimestamp(3).toInstant(),
                        row.getTimestamp(4).toInstant(),
                        row.getString(5)))
            .optional();
    Instant now = clock.instant();
    if (stored.isEmpty()
        || !selectedAuthenticationMode()
        || !locked.accountCurrent()
        || !permitted(locked.parent(), purpose, now)) {
      return Optional.empty();
    }
    StoredChallenge challenge = stored.orElseThrow();
    if (!purpose.wireValue().equals(challenge.purpose())
        || now.isBefore(challenge.createdAt())
        || !now.isBefore(challenge.expiresAt())
        || challenge.expiresAt().isAfter(locked.parent().expiresAt())) {
      return Optional.empty();
    }
    AuthenticationSession parent = locked.parent();
    return Optional.of(
        new ConsumedChallenge(
            parent.reference(),
            parent.account(),
            parent.epoch(),
            purpose,
            challenge.challenge(),
            challenge.expiresAt(),
            challenge.recoveryCodeVerifier()));
  }

  private LockedParent lockParent(UUID reference, boolean issuing) {
    Optional<AuthenticationSession> observed = findParent(reference, false);
    if (observed.isEmpty()) {
      return new LockedParent(null, false);
    }
    AuthenticationSession preliminary = observed.orElseThrow();
    boolean accountCurrent = true;
    if (preliminary.account() != null) {
      // Exclusive issuance serializes the per-account cap; consumption retains a shared account
      // lock, preventing concurrent revocation until the challenge deletion has committed.
      Optional<Long> epoch =
          jdbc.sql(
                  "SELECT authority_epoch FROM authentication_account WHERE account_id = ? "
                      + "AND status = 'active' "
                      + (issuing ? "FOR UPDATE" : "FOR SHARE"))
              .param(preliminary.account())
              .query(Long.class)
              .optional();
      accountCurrent = epoch.isPresent() && epoch.orElseThrow() == preliminary.epoch();
    }
    Optional<AuthenticationSession> current = findParent(reference, true);
    if (current.isEmpty()) {
      return new LockedParent(null, false);
    }
    AuthenticationSession parent = current.orElseThrow();
    return new LockedParent(
        parent,
        accountCurrent
            && Objects.equals(parent.account(), preliminary.account())
            && parent.epoch() == preliminary.epoch());
  }

  private boolean selectedAuthenticationMode() {
    return jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton")
        .query(String.class)
        .optional()
        .filter("single-user-auth"::equals)
        .isPresent();
  }

  private Optional<AuthenticationSession> findParent(UUID reference, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + AuthenticationSessionRows.COLUMNS
                + " FROM authentication_session_authority WHERE session_reference = ? "
                + (lock ? "FOR UPDATE" : ""))
        .param(reference)
        .query(AuthenticationSessionRows::read)
        .optional();
  }

  private static boolean permitted(
      AuthenticationSession parent, AuthenticationChallengePurpose purpose, Instant now) {
    return parent != null
        && parent.isUnexpired(now)
        && purpose.permits(parent.state(), parent.recoveryKind(), parent.loginAt() != null)
        && (!FULL.equals(parent.state())
            || AuthenticationLifetime.fullIsCurrent(parent.loginAt(), parent.bothFactorsAt(), now))
        && (purpose != AuthenticationChallengePurpose.CREDENTIAL
            || AuthenticationLifetime.recentBothFactors(parent.bothFactorsAt(), now));
  }

  private static void requirePublicationTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Challenge issuance requires a publication transaction.");
    }
  }

  /**
   * Immutable internal proof receipt; publication must recheck captured account/session authority.
   */
  public record ConsumedChallenge(
      UUID sessionReference,
      UUID account,
      long epoch,
      AuthenticationChallengePurpose purpose,
      byte[] challenge,
      Instant expiresAt,
      String recoveryCodeVerifier) {
    /** Copies the consumed nonce so callers cannot mutate a proof receipt. */
    public ConsumedChallenge {
      challenge = challenge.clone();
    }

    @Override
    public byte[] challenge() {
      return challenge.clone();
    }

    @Override
    public String toString() {
      return "ConsumedChallenge[redacted]";
    }
  }

  private record LockedParent(AuthenticationSession parent, boolean accountCurrent) {}

  private record StoredChallenge(
      byte[] challenge,
      String purpose,
      Instant createdAt,
      Instant expiresAt,
      String recoveryCodeVerifier) {
    @Override
    public String toString() {
      return "StoredChallenge[redacted]";
    }
  }
}
