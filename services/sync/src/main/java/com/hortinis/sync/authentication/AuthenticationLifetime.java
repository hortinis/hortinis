package com.hortinis.sync.authentication;

import java.time.Duration;
import java.time.Instant;

/** Trusted-clock ceremony and factor limits; Spring Session owns request inactivity. */
public final class AuthenticationLifetime {
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

  /** Completed factors remain bounded independently of Spring Session inactivity. */
  public static boolean fullIsCurrent(Instant login, Instant bothFactorsAt, Instant now) {
    return login != null
        && bothFactorsAt != null
        && !now.isBefore(bothFactorsAt)
        && !bothFactorsAt.isBefore(login)
        && isUnexpired(login, login.plus(FULL_ABSOLUTE), now);
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
