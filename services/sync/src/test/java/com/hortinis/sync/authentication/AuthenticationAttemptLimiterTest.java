package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthenticationAttemptLimiterTest {
  private static final String GARDENER = "gardener";
  private static final String TRUSTED_SOURCE = "trusted-source";
  private static final String EXTRA = "extra";
  private static final String SOURCE_ZERO = "source-0";

  @Test
  void boundsLoginAndTrustedSourceIndependentlyWithRollingEquality() {
    MutableClock clock = new MutableClock();
    AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
    for (int index = 0; index < 5; index++) {
      assertThat(limiter.admit(GARDENER, TRUSTED_SOURCE)).isTrue();
    }
    assertThat(limiter.admit(GARDENER, "other-source")).isFalse();
    for (int index = 0; index < 15; index++) {
      assertThat(limiter.admit("other-" + index, TRUSTED_SOURCE)).isTrue();
    }
    assertThat(limiter.admit("new-login", TRUSTED_SOURCE)).isFalse();
    clock.advance(59);
    assertThat(limiter.admit(GARDENER, TRUSTED_SOURCE)).isFalse();
    clock.advance(1);
    assertThat(limiter.admit(GARDENER, TRUSTED_SOURCE)).isTrue();
  }

  @Test
  void boundsInstallationAcrossDistinctSubjectsAndDeniesClockRollback() {
    MutableClock clock = new MutableClock();
    AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
    for (int index = 0; index < 60; index++) {
      assertThat(limiter.admit("login-" + index, "source-" + index)).isTrue();
    }
    assertThat(limiter.admit(EXTRA, EXTRA)).isFalse();
    clock.advance(-1);
    assertThat(limiter.admit("other", "other")).isFalse();
    clock.advance(61);
    assertThat(limiter.admit(EXTRA, EXTRA)).isTrue();
    assertThat(new AuthenticationAttemptLimiter(clock).admit("login-0", SOURCE_ZERO)).isTrue();
  }

  @Test
  void failsClosedAtCapacityWithoutEvictingRestrictionsAndPurgesAtIdleBoundary() {
    MutableClock clock = new MutableClock();
    AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock, 6);
    for (int index = 0; index < 3; index++) {
      assertThat(limiter.admit("fixture-" + index, "source-" + index)).isTrue();
    }
    assertThat(limiter.retainedKeyCount()).isEqualTo(6);
    assertThat(limiter.admit("new-fixture", "new-source")).isFalse();
    for (int index = 0; index < 4; index++) {
      assertThat(limiter.admit("fixture-0", SOURCE_ZERO)).isTrue();
    }
    assertThat(limiter.admit("fixture-0", SOURCE_ZERO)).isFalse();
    clock.advance(60);
    assertThat(limiter.admit("new-fixture", "new-source")).isFalse();
    assertThat(limiter.admit("fixture-0", SOURCE_ZERO)).isTrue();
    clock.advance(900);
    limiter.cleanup();
    assertThat(limiter.retainedKeyCount()).isZero();
    assertThat(limiter.admit("new-fixture", "new-source")).isTrue();
  }

  @Test
  void deletesIdleKeysAndBoundsUntrustedAdapterInputBeforeHmacWork() {
    MutableClock clock = new MutableClock();
    AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
    assertThat(limiter.admit("fixture", "source")).isTrue();
    assertThat(limiter.retainedKeyCount()).isEqualTo(2);
    clock.advance(899);
    limiter.cleanup();
    assertThat(limiter.retainedKeyCount()).isEqualTo(2);
    clock.advance(60);
    limiter.cleanup();
    assertThat(limiter.retainedKeyCount()).isZero();
    assertThat(limiter.admit(null, "source")).isFalse();
    assertThat(limiter.admit("fixture", "x".repeat(257))).isFalse();
    assertThat(limiter.admit("x".repeat(65), "source")).isFalse();
    assertThat(limiter.retainedKeyCount()).isZero();
  }

  @Test
  void sourceOnlyProofsShareExistingBoundsWithoutInventingOrRetainingLoginIdentity() {
    MutableClock clock = new MutableClock();
    AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
    for (int index = 0; index < 19; index++) {
      assertThat(limiter.admitSource(TRUSTED_SOURCE)).isTrue();
    }
    assertThat(limiter.retainedKeyCount()).isEqualTo(1);
    assertThat(limiter.admit(GARDENER, TRUSTED_SOURCE)).isTrue();
    assertThat(limiter.admitSource(TRUSTED_SOURCE)).isFalse();
    assertThat(limiter.admit(GARDENER, TRUSTED_SOURCE)).isFalse();
    for (int index = 0; index < 40; index++) {
      assertThat(limiter.admitSource("independent-" + index)).isTrue();
    }
    assertThat(limiter.admitSource(EXTRA)).isFalse();
    clock.advance(60);
    assertThat(limiter.admitSource(TRUSTED_SOURCE)).isTrue();
    assertThat(limiter.admitSource(null)).isFalse();
    assertThat(limiter.admitSource("x".repeat(257))).isFalse();
  }

  private static final class MutableClock extends Clock {
    private Instant current = Instant.parse("2026-10-11T12:00:00Z");

    void advance(long seconds) {
      current = current.plusSeconds(seconds);
    }

    @Override
    public Instant instant() {
      return current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
