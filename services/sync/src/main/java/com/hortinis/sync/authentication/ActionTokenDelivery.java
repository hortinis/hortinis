package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal R3 replacement/delivery boundary; no HTTP or host workflow is exposed. */
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

  /** Admission precedes the trusted caller's transactional account selection or reservation. */
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
                var token = tokens.issue(selectAccount.get(), purpose);
                if (!canonical.equals(EmailAddress.canonical(token.recipient()))) {
                  throw new AuthenticationPreconditionException();
                }
                return token;
              });
      boolean accepted = delivery.deliver(java.util.Objects.requireNonNull(issued));
      LOGGER
          .atInfo()
          .addKeyValue("event", "action_mail_attempt")
          .addKeyValue("outcome", accepted ? "accepted" : "unavailable")
          .addKeyValue("request_reference", UUID.randomUUID())
          .log("Action mail attempt completed.");
      return accepted ? Outcome.ACCEPTED : Outcome.UNAVAILABLE;
    }
  }

  /** Internal fixed outcomes; R4 owns their generic browser and controlled host mapping. */
  public enum Outcome {
    ACCEPTED,
    UNAVAILABLE,
    LIMITED
  }
}
