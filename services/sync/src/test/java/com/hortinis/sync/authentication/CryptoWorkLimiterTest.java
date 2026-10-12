package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CryptoWorkLimiterTest {
  @Test
  void deniesThirdConcurrentJobAndReleasesPermitsAfterSuccessAndFailure() throws Exception {
    CryptoWorkLimiter limiter = new CryptoWorkLimiter();
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> limiter.execute(() -> hold(entered, release)));
      var second = executor.submit(() -> limiter.execute(() -> hold(entered, release)));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> limiter.execute(() -> "third"))
            .isInstanceOf(AuthenticationOverloadedException.class);
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("completed");
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("completed");
    }
    assertThatThrownBy(
            () ->
                limiter.execute(
                    () -> {
                      throw new IllegalStateException("synthetic failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(limiter.execute(() -> "recovered")).isEqualTo("recovered");
  }

  private static String hold(CountDownLatch entered, CountDownLatch release) {
    entered.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test synchronization timed out.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Test synchronization interrupted.");
    }
    return "completed";
  }
}
