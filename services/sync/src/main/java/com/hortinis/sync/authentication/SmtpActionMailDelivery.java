package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence.IssuedToken;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Boot supplies JavaMailSender; the adapter supplies two fixed plain-text messages only. */
public final class SmtpActionMailDelivery implements ActionMailDelivery {
  private final JavaMailSender sender;
  private final String from;
  private final String origin;

  /** Validates trusted configuration without connecting or sending a startup message. */
  public SmtpActionMailDelivery(JavaMailSender sender, String from, String origin) {
    EmailAddress.canonical(from);
    this.sender = java.util.Objects.requireNonNull(sender);
    this.from = from;
    this.origin = TrustedApplicationOrigin.parse(origin).toASCIIString();
  }

  @Override
  public boolean deliver(IssuedToken token) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Mail delivery requires a completed transaction.");
    }
    EmailAddress.canonical(token.recipient());
    if (!AuthenticationSecrets.isRandomValue(token.secret())) {
      throw new IllegalArgumentException("Invalid action delivery material.");
    }
    boolean invitation = token.purpose() == ActionTokenPurpose.INVITATION;
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(token.recipient());
    message.setSubject(invitation ? "Hortinis invitation" : "Hortinis password reset");
    message.setText(
        "Hortinis\n\n"
            + (invitation ? "Accept your invitation" : "Reset your password")
            + ". This link expires at "
            + token.expiresAt()
            + ".\n\n"
            + origin
            + token.purpose().path()
            + "#token="
            + token.secret()
            + "\n\nOpening this link does not complete the action.\n");
    try {
      sender.send(message);
      return true;
    } catch (MailException exception) {
      // SMTP may have accepted the message: retain the committed token and never retry here.
      return false;
    }
  }
}
