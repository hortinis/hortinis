package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence.IssuedToken;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SmtpActionMailDeliveryTest {
  private static final String ORIGIN = "https://garden.example.test:8443";
  private static final String FROM = "Hortinis@Example.test";
  private static final String RECIPIENT = "Gardener@Example.test";
  private final JavaMailSender sender = mock(JavaMailSender.class);

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void minimalPlainTextUsesFixedOriginPurposeAndStoredRecipient(ActionTokenPurpose purpose) {
    var token = token(purpose);
    assertThat(new SmtpActionMailDelivery(sender, FROM, ORIGIN).deliver(token)).isTrue();
    var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(message.capture());
    assertThat(message.getValue().getFrom()).isEqualTo(FROM);
    assertThat(message.getValue().getTo()).containsExactly(RECIPIENT);
    assertThat(message.getValue().getCc()).isNull();
    assertThat(message.getValue().getBcc()).isNull();
    assertThat(message.getValue().getReplyTo()).isNull();
    assertThat(message.getValue().getText())
        .contains(
            "Hortinis",
            token.expiresAt().toString(),
            ORIGIN + purpose.path() + "#token=" + token.secret())
        .doesNotContain("?token=", "<img", "<script", RECIPIENT);
    assertThat(message.getValue().getSubject())
        .isEqualTo(
            purpose == ActionTokenPurpose.INVITATION
                ? "Hortinis invitation"
                : "Hortinis password reset");
  }

  @Test
  void providerFailureIsReducedToFixedOutcomeWithNoRetry() {
    doThrow(new MailSendException("synthetic recipient body token canary"))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    assertThat(
            new SmtpActionMailDelivery(sender, FROM, ORIGIN)
                .deliver(token(ActionTokenPurpose.RESET)))
        .isFalse();
    verify(sender).send(any(SimpleMailMessage.class));
  }

  @Test
  void activeTransactionOrAfterCommitSynchronizationCannotSend() {
    var adapter = new SmtpActionMailDelivery(sender, FROM, ORIGIN);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> adapter.deliver(token(ActionTokenPurpose.RESET)))
          .hasMessage("Mail delivery requires a completed transaction.");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> adapter.deliver(token(ActionTokenPurpose.RESET)))
          .hasMessage("Mail delivery requires a completed transaction.");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(sender);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://garden.example.test",
        "https://user@garden.example.test",
        "https://garden.example.test/",
        "https://garden.example.test?return=bad",
        "https://garden.example.test#bad",
        "https://garden.example.test:0",
        "https://garden.example.test:65536",
        "https://garden.example.test:"
      })
  void untrustedLinkOriginsFailWithFixedDiagnostic(String origin) {
    assertThatThrownBy(() -> new SmtpActionMailDelivery(sender, FROM, origin))
        .hasMessage("A trusted HTTPS application origin is required.");
    verifyNoInteractions(sender);
  }

  private static IssuedToken token(ActionTokenPurpose purpose) {
    return new IssuedToken(
        RECIPIENT,
        AuthenticationSecrets.randomValue(),
        purpose,
        Instant.parse("2026-10-18T12:00:00Z"));
  }
}
