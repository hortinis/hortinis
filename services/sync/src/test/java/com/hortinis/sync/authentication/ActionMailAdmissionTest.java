package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Transport capacity only; shared rolling-accounting evidence uses real PostgreSQL. */
class ActionMailAdmissionTest {
  @Test
  void twoConcurrentSendsHaveNoQueueAndCloseIsIdempotent() {
    var admission = new ActionMailAdmission();
    try (var first = admission.reserve();
        var second = admission.reserve()) {
      assertThat(first.allowed()).isTrue();
      assertThat(second.allowed()).isTrue();
      try (var denied = admission.reserve()) {
        assertThat(denied.allowed()).isFalse();
      }
      first.close();
      first.close();
      try (var third = admission.reserve();
          var denied = admission.reserve()) {
        assertThat(third.allowed()).isTrue();
        assertThat(denied.allowed()).isFalse();
      }
    }
    try (var permit = admission.reserve()) {
      assertThat(permit.allowed()).isTrue();
      assertThat(permit.toString()).isEqualTo("MailPermit[redacted]");
    }
  }
}
