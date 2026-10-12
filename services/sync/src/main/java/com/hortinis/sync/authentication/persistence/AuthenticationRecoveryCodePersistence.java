package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationChallengePurpose;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current-authority replacement and atomic code consumption within factor publication. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationRecoveryCodePersistence {
  private static final int CODE_COUNT = 10;
  private static final java.time.Duration WARNING_PERIOD = java.time.Duration.ofDays(30);
  private static final String DUMMY_VERIFIER = "0".repeat(64);
  private final JdbcClient jdbc;
  private final Clock clock;
  private final CurrentAuthenticationAuthorityPersistence authority;

  /** Shares the trusted clock and the caller's account/session publication transaction. */
  public AuthenticationRecoveryCodePersistence(
      JdbcClient jdbc, Clock clock, CurrentAuthenticationAuthorityPersistence authority) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.authority = authority;
  }

  /**
   * Replaces the entire offline code set, requiring current full and recent two-factor authority.
   */
  public IssuedCodes regenerate(CurrentAuthenticationAuthorityPersistence.FullAuthority observed) {
    requirePublicationTransaction();
    if (!lockCurrentAccount(observed.account(), observed.epoch())) {
      throw new AuthenticationRequiredException();
    }
    authority.requireCurrent(
        observed.account(),
        observed.sessionReference(),
        observed.epoch(),
        observed.instance(),
        observed.scope(),
        observed.mode());
    Instant now = clock.instant();
    Optional<Instant> verifiedAt =
        jdbc.sql(
                "SELECT both_factors_at FROM authentication_session_authority "
                    + "WHERE session_reference = ?")
            .param(observed.sessionReference())
            .query((row, number) -> row.getTimestamp(1).toInstant())
            .optional();
    if (verifiedAt.isEmpty()
        || !AuthenticationLifetime.recentBothFactors(verifiedAt.orElseThrow(), now)) {
      throw new AuthenticationFailedException();
    }
    return replaceCodes(observed.account(), now);
  }

  /** Only controlled setup calls this for its newly inserted account in the same transaction. */
  IssuedCodes initializeForSetup(UUID account) {
    requirePublicationTransaction();
    Instant now = clock.instant();
    boolean fresh =
        jdbc.sql(
                "SELECT status = 'active' "
                    + "AND NOT EXISTS (SELECT 1 FROM authentication_recovery_code WHERE "
                    + "account_id = ?) "
                    + "FROM authentication_account WHERE account_id = ? FOR UPDATE")
            .params(account, account)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (!fresh) {
      throw new AuthenticationFailedException();
    }
    return replaceCodes(account, now);
  }

  /** Minimized unused-code projection under the caller's current full account/session locks. */
  public CodeStatus status(UUID account) {
    requirePublicationTransaction();
    Instant now = clock.instant();
    return jdbc.sql(
            "SELECT count(*), min(expires_at) FROM authentication_recovery_code "
                + "WHERE account_id = ? AND expires_at > ? AND created_at <= ?")
        .params(account, Timestamp.from(now), Timestamp.from(now))
        .query(
            (row, number) -> {
              int remaining = row.getInt(1);
              Instant expiry = row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant();
              if (remaining < 0 || remaining > CODE_COUNT) {
                throw new AuthenticationFailedException();
              }
              return new CodeStatus(
                  remaining, expiry, expiry != null && !now.isBefore(expiry.minus(WARNING_PERIOD)));
            })
        .single();
  }

  private IssuedCodes replaceCodes(UUID account, Instant now) {
    Set<String> unique = new HashSet<>();
    while (unique.size() < CODE_COUNT) {
      unique.add(AuthenticationSecrets.randomValue());
    }
    List<String> codes = new ArrayList<>(unique);
    Instant expiresAt = now.plus(AuthenticationLifetime.RECOVERY_CODE_ABSOLUTE);
    jdbc.sql("DELETE FROM authentication_recovery_code WHERE account_id = ?")
        .param(account)
        .update();
    for (String code : codes) {
      jdbc.sql(
              "INSERT INTO authentication_recovery_code "
                  + "(account_id, secret_verifier, created_at, expires_at) VALUES (?, ?, ?, ?)")
          .params(
              account,
              AuthenticationSecrets.digest(code),
              Timestamp.from(now),
              Timestamp.from(expiresAt))
          .update();
    }
    return new IssuedCodes(codes, expiresAt);
  }

  /**
   * Called after the independent recovery factor succeeds, in the same factor-change transaction.
   */
  public boolean consume(UUID account, long expectedEpoch, String code) {
    requirePublicationTransaction();
    if (!AuthenticationSecrets.isRandomValue(code)) {
      return false;
    }
    return consumeVerifier(account, expectedEpoch, AuthenticationSecrets.digest(code));
  }

  /** Only a consumed server challenge plus actual assertion proof may supply a retained digest. */
  public boolean consumeForPasswordReset(
      UUID account,
      long expectedEpoch,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      BoundWebAuthnOperations.VerifiedCredential proof) {
    requirePublicationTransaction();
    if (receipt == null
        || proof == null
        || receipt.purpose() != AuthenticationChallengePurpose.RECOVER_PASSWORD
        || proof.kind() != BoundWebAuthnOperations.ProofKind.ASSERTION
        || !MessageDigest.isEqual(receipt.challenge(), proof.challenge())) {
      return false;
    }
    return consumeVerifier(account, expectedEpoch, receipt.recoveryCodeVerifier());
  }

  private boolean consumeVerifier(UUID account, long expectedEpoch, String candidate) {
    requirePublicationTransaction();
    if (candidate == null || !candidate.matches("[a-f0-9]{64}")) {
      return false;
    }
    if (!lockCurrentAccount(account, expectedEpoch) || !selectedAuthenticationMode()) {
      return false;
    }
    Instant now = clock.instant();
    jdbc.sql("DELETE FROM authentication_recovery_code WHERE account_id = ? AND expires_at <= ?")
        .params(account, Timestamp.from(now))
        .update();
    List<String> verifiers =
        jdbc.sql(
                "SELECT secret_verifier FROM authentication_recovery_code "
                    + "WHERE account_id = ? AND created_at <= ? AND expires_at > ? LIMIT 11")
            .params(account, Timestamp.from(now), Timestamp.from(now))
            .query(String.class)
            .list();
    if (verifiers.size() > CODE_COUNT) {
      return false;
    }
    String matching = null;
    // Fixed bounded comparison work for validly shaped codes, including an absent code set.
    for (int index = 0; index < CODE_COUNT; index++) {
      String verifier = index < verifiers.size() ? verifiers.get(index) : DUMMY_VERIFIER;
      boolean matches =
          MessageDigest.isEqual(
              candidate.getBytes(StandardCharsets.US_ASCII),
              verifier.getBytes(StandardCharsets.US_ASCII));
      if (matches && index < verifiers.size()) {
        matching = verifier;
      }
    }
    return matching != null
        && jdbc.sql(
                    "DELETE FROM authentication_recovery_code "
                        + "WHERE account_id = ? AND secret_verifier = ?")
                .params(account, matching)
                .update()
            == 1;
  }

  private boolean lockCurrentAccount(UUID account, long epoch) {
    Optional<Long> current =
        jdbc.sql(
                "SELECT authority_epoch FROM authentication_account "
                    + "WHERE account_id = ? AND status = 'active' FOR UPDATE")
            .param(account)
            .query(Long.class)
            .optional();
    return epoch > 0 && current.isPresent() && current.orElseThrow() == epoch;
  }

  private boolean selectedAuthenticationMode() {
    return jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton")
        .query(String.class)
        .optional()
        .filter("single-user-auth"::equals)
        .isPresent();
  }

  private static void requirePublicationTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Recovery codes require a factor publication transaction.");
    }
  }

  /** Non-secret availability; it cannot authorize reset or expose the original code set. */
  public record CodeStatus(int remaining, Instant expiresAt, boolean expiresSoon) {
    @Override
    public String toString() {
      return "RecoveryCodeStatus[redacted]";
    }
  }

  /** One-time internal result; secrets must not enter logs, session attributes or diagnostics. */
  public record IssuedCodes(List<String> codes, Instant expiresAt) {
    /** Copies the fixed code set so callers cannot change the issued values. */
    public IssuedCodes {
      codes = List.copyOf(codes);
    }

    @Override
    public String toString() {
      return "IssuedCodes[redacted]";
    }
  }
}
