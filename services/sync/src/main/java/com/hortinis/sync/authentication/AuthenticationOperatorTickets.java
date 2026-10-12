package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit host capability, constructed only by a controlled command; never a browser service. */
public final class AuthenticationOperatorTickets {
  private final AuthenticationOperatorTicketPersistence tickets;
  private final OperatorTicketFiles files;
  private final TransactionTemplate transactions;

  /** Requires an already validated protected directory and the shared database manager. */
  public AuthenticationOperatorTickets(
      AuthenticationOperatorTicketPersistence tickets,
      OperatorTicketFiles files,
      PlatformTransactionManager transactionManager) {
    this.tickets = tickets;
    this.files = files;
    transactions = new TransactionTemplate(transactionManager);
  }

  /** Issues one installation-bound setup ticket; the returned path contains no secret material. */
  public IssuedTicket issueSetup() {
    return issue(null);
  }

  /** Issues one target-bound recovery ticket without changing credentials or data authority. */
  public IssuedTicket issueRecovery(UUID account) {
    if (account == null) {
      throw new IllegalArgumentException("A controlled recovery target is required.");
    }
    return issue(account);
  }

  /** Startup/periodic command integration uses the same installation lock as every issuer. */
  public void reconcile() {
    requireIndependentInvocation();
    transactions.executeWithoutResult(status -> files.cleanup(tickets.reconcileReferences()));
  }

  private IssuedTicket issue(UUID account) {
    requireIndependentInvocation();
    try {
      return Objects.requireNonNull(
          transactions.execute(
              status -> {
                String secret = AuthenticationSecrets.randomValue();
                String verifier = AuthenticationSecrets.digest(secret);
                UUID reference =
                    account == null
                        ? tickets.issueSetup(verifier)
                        : tickets.issueRecovery(account, verifier);
                return new IssuedTicket(files.create(reference, secret));
              }));
    } finally {
      // The first transaction finished: reconciliation cannot erase an uncommitted new file.
      reconcile();
    }
  }

  private static void requireIndependentInvocation() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Controlled ticket issuance requires independent transactions.");
    }
  }

  /** The protected output path is host-only and deliberately redacted in diagnostics. */
  public record IssuedTicket(Path file) {
    @Override
    public String toString() {
      return "IssuedTicket[redacted]";
    }
  }
}
