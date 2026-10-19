package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.MailIssuancePersistence;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared issuance transaction and post-commit delivery for HTTP reset and host invitations. */
public final class ActionTokenDelivery {
  private static final Logger LOGGER = LoggerFactory.getLogger(ActionTokenDelivery.class);
  private final ActionTokenPersistence tokens;
  private final ActionMailAdmission admission;
  private final ActionMailDelivery delivery;
  private final MailIssuancePersistence quotas;
  private final TransactionTemplate transactions;

  /** A completed issuance transaction always precedes synchronous network work. */
  public ActionTokenDelivery(
      ActionTokenPersistence tokens,
      ActionMailAdmission admission,
      MailIssuancePersistence quotas,
      ActionMailDelivery delivery,
      PlatformTransactionManager transactions) {
    this.tokens = tokens;
    this.quotas = quotas;
    this.admission = admission;
    this.delivery = delivery;
    this.transactions = new TransactionTemplate(transactions);
  }

  /**
   * Shared accounting precedes transactional account selection; ineligible issuance rolls back.
   * Only eligible committed tokens spend quota or SMTP capacity.
   */
  public Outcome issueAndDeliver(
      String email, ActionTokenPurpose purpose, Supplier<UUID> selectAccount) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Action delivery requires independent issuance.");
    }
    String canonical = EmailAddress.canonical(email);
    var held = new AtomicReference<ActionMailAdmission.Permit>();
    var recipientSelected = new AtomicBoolean();
    var outcome = new AtomicReference<>(Outcome.LIMITED);
    try {
      var issued =
          transactions.execute(
              status -> {
                if (!quotas.reserve(canonical, purpose)) {
                  return null;
                }
                UUID account = selectAccount.get();
                if (account == null && purpose == ActionTokenPurpose.RESET) {
                  status.setRollbackOnly();
                  outcome.set(Outcome.INELIGIBLE);
                  return null;
                }
                recipientSelected.set(true);
                var token = tokens.issue(account, purpose);
                if (!canonical.equals(EmailAddress.canonical(token.recipient()))) {
                  throw new AuthenticationPreconditionException();
                }
                var permit = admission.reserve();
                held.set(permit);
                if (!permit.allowed()) {
                  status.setRollbackOnly();
                  return null;
                }
                return token;
              });
      if (issued == null) {
        return outcome.get();
      }
      boolean accepted = delivery.deliver(issued);
      LOGGER
          .atInfo()
          .addKeyValue("event", "action_mail_attempt")
          .addKeyValue("outcome", accepted ? "accepted" : "unavailable")
          .addKeyValue("request_reference", UUID.randomUUID())
          .log("Action mail attempt completed.");
      return accepted ? Outcome.ACCEPTED : Outcome.UNAVAILABLE;
    } catch (DataAccessException | TransactionException exception) {
      if (purpose != ActionTokenPurpose.RESET || !recipientSelected.get()) {
        throw exception;
      }
      // A recipient-specific issuance/commit failure must not become a public existence oracle.
      LOGGER
          .atWarn()
          .addKeyValue("event", "action_issuance_unavailable")
          .addKeyValue("request_reference", UUID.randomUUID())
          .log("Action issuance is unavailable.");
      return Outcome.UNAVAILABLE;
    } finally {
      if (held.get() != null) {
        held.get().close();
      }
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
