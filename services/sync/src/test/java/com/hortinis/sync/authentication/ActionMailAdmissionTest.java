package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ActionMailAdmissionTest {
  private static final String MAILBOX = "mailbox@example.test";
  private static final String SECOND = "second@example.test";
  private static final String THIRD = "third@example.test";
  private final MutableClock clock = new MutableClock();
  private final ActionMailAdmission admission = new ActionMailAdmission(clock);

  @Test
  void cooldownCaseFoldingAndHourlyBoundaryAreSharedAcrossPurposes() {
    attempt("Mailbox@Example.test", true);
    attempt(MAILBOX, false);
    clock.advance(59);
    attempt("Mailbox@Example.test", false);
    clock.advance(1);
    attempt(MAILBOX, true);
    clock.advance(60);
    attempt(MAILBOX, true);
    clock.advance(60);
    attempt(MAILBOX, false);
    clock.advance(3420);
    attempt(MAILBOX, true);
  }

  @Test
  void dailyLimitExpiresAtExactRollingBoundary() {
    for (int index = 0; index < 10; index++) {
      if (index > 0) {
        clock.advance(index % 3 == 0 ? 3600 : 60);
      }
      attempt(MAILBOX, true);
    }
    clock.advance(3600);
    attempt(MAILBOX, false);
    clock.advance(Duration.ofDays(1).toSeconds() - clock.elapsed);
    attempt(MAILBOX, true);
  }

  @Test
  void installationHourlyAndDailyLimitsApplyBeforeAnyLookup() {
    for (int group = 0; group < 4; group++) {
      int attempts = group == 3 ? 10 : 30;
      for (int index = 0; index < attempts; index++) {
        attempt("unknown" + group + "x" + index + "@example.test", true);
      }
      assertThatThrownBy(() -> admission.reserve("next@example.test"))
          .isInstanceOf(AuthenticationOverloadedException.class);
      clock.advance(3600);
    }
    clock.advance(Duration.ofDays(1).toSeconds() - clock.elapsed);
    attempt("next@example.test", true);
  }

  @Test
  void twoConcurrentReservationsHaveNoQueueAndCloseIsIdempotent() {
    try (var first = admission.reserve("first@example.test");
        var second = admission.reserve(SECOND)) {
      assertThat(first.allowed()).isTrue();
      assertThat(second.allowed()).isTrue();
      assertThatThrownBy(() -> admission.reserve(THIRD))
          .isInstanceOf(AuthenticationOverloadedException.class);
      first.close();
      first.close();
      try (var third = admission.reserve(THIRD)) {
        assertThat(third.allowed()).isTrue();
        assertThatThrownBy(() -> admission.reserve("fourth@example.test"))
            .isInstanceOf(AuthenticationOverloadedException.class);
      }
    }
    attempt("fourth@example.test", true);
  }

  @Test
  void fullKeyCapacityDeniesWithoutEvictionAndIdleCleanupIsBounded() {
    var small = new ActionMailAdmission(clock, 1);
    try (var first = small.reserve("first@example.test")) {
      assertThat(first.allowed()).isTrue();
    }
    clock.advance(3600);
    assertThatThrownBy(() -> small.reserve(SECOND))
        .isInstanceOf(AuthenticationOverloadedException.class);
    clock.advance(23 * 3600);
    small.cleanup();
    try (var second = small.reserve(SECOND)) {
      assertThat(second.allowed()).isTrue();
    }
  }

  @Test
  void backwardClockAndInvalidInputDenyWithoutLeakingValues() {
    attempt(MAILBOX, true);
    clock.advance(-1);
    assertThatThrownBy(() -> admission.reserve(SECOND))
        .isInstanceOf(AuthenticationOverloadedException.class);
    assertThatThrownBy(() -> admission.reserve("mailbox@example.test\n"))
        .hasMessage("Invalid email address.");
  }

  @Test
  void requestAndMailKeysShareOneAggregateCapacity() {
    var capacity = new AuthenticationAdmissionCapacity(3);
    var attempts = new AuthenticationAttemptLimiter(clock, capacity);
    var mail = new ActionMailAdmission(clock, capacity);
    assertThat(attempts.admit("first@example.test", "synthetic-source")).isTrue();
    try (var permit = mail.reserve(MAILBOX)) {
      assertThat(permit.allowed()).isTrue();
    }
    assertThat(attempts.admit("second@example.test", "synthetic-source")).isFalse();
    assertThatThrownBy(() -> mail.reserve(THIRD))
        .isInstanceOf(AuthenticationOverloadedException.class);
    clock.advance(15 * 60);
    attempts.cleanup();
    try (var permit = mail.reserve(THIRD)) {
      assertThat(permit.allowed()).isTrue();
    }
  }

  private void attempt(String email, boolean expected) {
    try (var permit = admission.reserve(email)) {
      assertThat(permit.allowed()).isEqualTo(expected);
    }
  }

  static final class MutableClock extends Clock {
    private final Instant initial = Instant.parse("2026-10-17T12:00:00Z");
    private long elapsed;

    void advance(long seconds) {
      elapsed += seconds;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return initial.plusSeconds(elapsed);
    }
  }
}
