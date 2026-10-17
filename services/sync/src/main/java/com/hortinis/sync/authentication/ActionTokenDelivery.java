package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared issuance transaction and post-commit delivery for HTTP reset and host invitations. */
public final class ActionTokenDelivery {
  private static final Logger LOGGER = LoggerFactory.getLogger(ActionTokenDelivery.class);
  private final ActionTokenPersistence tokens;
  private final ActionMailAdmission admission;
  private final ActionMailDelivery delivery;
  private final TransactionTemplate transactions;

  /** A completed issuance transaction always precedes synchronous network work. */
  public ActionTokenDelivery(
      ActionTokenPersistence tokens,
      ActionMailAdmission admission,
      ActionMailDelivery delivery,
      PlatformTransactionManager transactions) {
    this.tokens = tokens;
    this.admission = admission;
    this.delivery = delivery;
    this.transactions = new TransactionTemplate(transactions);
  }

  /**
   * Admission precedes transactional account selection/reservation. Only reset may select no
   * account.
   */
  public Outcome issueAndDeliver(
      String email, ActionTokenPurpose purpose, Supplier<UUID> selectAccount) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Action delivery requires independent issuance.");
    }
    String canonical = EmailAddress.canonical(email);
    try (var permit = admission.reserve(canonical)) {
      if (!permit.allowed()) {
        return Outcome.LIMITED;
      }
      var issued =
          transactions.execute(
              status -> {
                UUID account = selectAccount.get();
                if (account == null && purpose == ActionTokenPurpose.RESET) {
                  return null;
                }
                var token = tokens.issue(account, purpose);
                if (!canonical.equals(EmailAddress.canonical(token.recipient()))) {
                  throw new AuthenticationPreconditionException();
                }
                return token;
              });
      if (issued == null) {
        return Outcome.INELIGIBLE;
      }
      boolean accepted = delivery.deliver(issued);
      LOGGER
          .atInfo()
          .addKeyValue("event", "action_mail_attempt")
          .addKeyValue("outcome", accepted ? "accepted" : "unavailable")
          .addKeyValue("request_reference", UUID.randomUUID())
          .log("Action mail attempt completed.");
      return accepted ? Outcome.ACCEPTED : Outcome.UNAVAILABLE;
    }
  }

  /**
   * Fixed internal outcomes; recipient-specific reset outcomes share one public acknowledgement.
   */
  public enum Outcome {
    INELIGIBLE,
    ACCEPTED,
    UNAVAILABLE,
    LIMITED
  }
}
