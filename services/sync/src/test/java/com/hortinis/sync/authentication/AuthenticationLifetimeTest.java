package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthenticationLifetimeTest {
  private static final Instant LOGIN = Instant.parse("2026-10-11T12:00:00Z");

  @Test
  void everyCeremonyRejectsFutureCreationAndExpiresAtItsExactDeadline() {
    Instant expires = LOGIN.plusSeconds(300);
    assertThat(AuthenticationLifetime.isUnexpired(LOGIN, expires, LOGIN)).isTrue();
    assertThat(AuthenticationLifetime.isUnexpired(LOGIN, expires, LOGIN.minusNanos(1))).isFalse();
    assertThat(AuthenticationLifetime.isUnexpired(LOGIN, expires, expires.minusNanos(1))).isTrue();
    assertThat(AuthenticationLifetime.isUnexpired(LOGIN, expires, expires)).isFalse();
  }

  @Test
  void fullAuthorityRequiresCompletedFactorsWithinItsLoginAndTrustedTime() {
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN, LOGIN)).isTrue();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, null, LOGIN)).isFalse();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN.minusNanos(1), LOGIN))
        .isFalse();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN.plusNanos(1), LOGIN))
        .isFalse();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN, LOGIN.plusSeconds(1800)))
        .isFalse();
  }

  @Test
  void idleExpiresAtEqualityAndBackgroundTimeCannotRenewIt() {
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN.plusSeconds(1799)))
        .isTrue();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN, LOGIN.plusSeconds(1800)))
        .isFalse();
    assertThat(
            AuthenticationLifetime.fullIsCurrent(
                LOGIN, LOGIN.plusSeconds(600), LOGIN.plusSeconds(2399)))
        .isTrue();
    assertThat(
            AuthenticationLifetime.fullIsCurrent(
                LOGIN, LOGIN.plusSeconds(600), LOGIN.plusSeconds(2400)))
        .isFalse();
  }

  @Test
  void interactionAndRotationNeverExtendAbsoluteExpiryAndFutureTimesDeny() {
    Instant interaction = LOGIN.plusSeconds(43199);
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, interaction, interaction)).isTrue();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, interaction, LOGIN.plusSeconds(43200)))
        .isFalse();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN.minusSeconds(1), LOGIN)).isFalse();
    assertThat(AuthenticationLifetime.fullIsCurrent(LOGIN, LOGIN.plusSeconds(1), LOGIN)).isFalse();
  }

  @Test
  void recentFactorsAndChallengeRespectExactParentBoundary() {
    assertThat(AuthenticationLifetime.recentBothFactors(LOGIN, LOGIN.plusSeconds(299))).isTrue();
    assertThat(AuthenticationLifetime.recentBothFactors(LOGIN, LOGIN.plusSeconds(300))).isFalse();
    assertThat(AuthenticationLifetime.recentBothFactors(LOGIN, LOGIN.minusSeconds(1))).isFalse();
    assertThat(AuthenticationLifetime.recentBothFactors(null, LOGIN)).isFalse();
    assertThat(AuthenticationLifetime.challengeExpiresAt(LOGIN, LOGIN.plusSeconds(60)))
        .isEqualTo(LOGIN.plusSeconds(60));
    assertThat(AuthenticationLifetime.challengeExpiresAt(LOGIN, LOGIN.plusSeconds(600)))
        .isEqualTo(LOGIN.plusSeconds(300));
  }
}
