package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Outcome;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Reason;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Native verification/logout events become fixed minimized audit rows; no event payload is stored.
 */
public final class AuthenticationAudit {
  private final AuthenticationSecurityEventPersistence events;
  private final TransactionTemplate transactions;

  /** Audit transactions use the same datasource without reproducing authentication publication. */
  public AuthenticationAudit(
      AuthenticationSecurityEventPersistence events, PlatformTransactionManager transactions) {
    this.events = events;
    this.transactions = new TransactionTemplate(transactions);
  }

  /** Provider success means password verification, before native session establishment. */
  @EventListener
  public void verified(AuthenticationSuccessEvent event) {
    append(Event.LOGIN, Outcome.SUCCEEDED, Reason.COMPLETED);
  }

  /** Every provider rejection has the same stored code, without identifying the account. */
  @EventListener
  public void failed(AbstractAuthenticationFailureEvent event) {
    append(Event.LOGIN, Outcome.DENIED, Reason.INVALID_PROOF);
  }

  /** Native logout reports only the fixed outcome. */
  @EventListener
  public void logout(LogoutSuccessEvent event) {
    append(Event.LOGOUT, Outcome.SUCCEEDED, Reason.COMPLETED);
  }

  private void append(Event event, Outcome outcome, Reason reason) {
    transactions.executeWithoutResult(status -> events.append(event, outcome, reason));
  }
}
