package com.hortinis.sync.authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Trusted-clock policy; expiry is exclusive and background traffic never renews authority. */
public final class AuthenticationLifetime {
  public static final Duration FULL_IDLE = Duration.ofMinutes(30);
  public static final Duration FULL_ABSOLUTE = Duration.ofHours(12);
  public static final Duration LIMITED_ABSOLUTE = Duration.ofMinutes(5);
  public static final Duration RECENT_AUTHENTICATION = Duration.ofMinutes(5);
  public static final Duration TICKET_ABSOLUTE = Duration.ofMinutes(10);
  public static final Duration RECOVERY_CODE_ABSOLUTE = Duration.ofDays(365);

  private AuthenticationLifetime() {}

  /** Session creation and expiration bound every ceremony, including anonymous authority. */
  public static boolean isUnexpired(Instant createdAt, Instant expiresAt, Instant now) {
    return !now.isBefore(createdAt) && now.isBefore(expiresAt);
  }

  /** Full authority also requires complete factors belonging to this login. */
  public static boolean fullIsCurrent(
      Instant login, Instant lastInteraction, Instant bothFactorsAt, Instant now) {
    return bothFactorsAt != null
        && !now.isBefore(bothFactorsAt)
        && !bothFactorsAt.isBefore(login)
        && fullIsCurrent(login, lastInteraction, now);
  }

  /** Tests both fixed login lifetime and last explicit user-interaction lifetime. */
  public static boolean fullIsCurrent(Instant login, Instant lastInteraction, Instant now) {
    Objects.requireNonNull(login);
    Objects.requireNonNull(lastInteraction);
    Objects.requireNonNull(now);
    return !lastInteraction.isBefore(login)
        && !now.isBefore(lastInteraction)
        && now.isBefore(login.plus(FULL_ABSOLUTE))
        && now.isBefore(lastInteraction.plus(FULL_IDLE));
  }

  /** Privileged authorization expires exactly five minutes after the complete factor proof. */
  public static boolean recentBothFactors(Instant verifiedAt, Instant now) {
    return verifiedAt != null
        && !now.isBefore(verifiedAt)
        && now.isBefore(verifiedAt.plus(RECENT_AUTHENTICATION));
  }

  /** A challenge cannot outlive the ceremony that authorized its issuance. */
  public static Instant challengeExpiresAt(Instant issuedAt, Instant parentExpiresAt) {
    Instant challengeLimit = issuedAt.plus(LIMITED_ABSOLUTE);
    return parentExpiresAt.isBefore(challengeLimit) ? parentExpiresAt : challengeLimit;
  }
}
