package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AccountActionPersistence;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence.CurrentAuthority;
import com.hortinis.sync.protocol.InvalidRequestException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Application action semantics only; Spring retains password login and session authority. */
public final class AccountActionService {
  private final AccountActionPersistence accounts;
  private final ActionTokenPersistence tokens;
  private final CurrentAuthenticationAuthorityPersistence authority;
  private final AuthenticationNativeSessions sessions;
  private final PasswordPolicy policy;
  private final PasswordVerifier passwords;
  private final ActionTokenDelivery delivery;
  private final TransactionTemplate transactions;

  /** Reuses the native password/session components and the shared transaction manager. */
  public AccountActionService(
      AccountActionPersistence accounts,
      ActionTokenPersistence tokens,
      CurrentAuthenticationAuthorityPersistence authority,
      AuthenticationNativeSessions sessions,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      ActionTokenDelivery delivery,
      PlatformTransactionManager transactions) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.authority = authority;
    this.sessions = sessions;
    this.policy = policy;
    this.passwords = passwords;
    this.delivery = delivery;
    this.transactions = new TransactionTemplate(transactions);
  }

  /** Unknown/ineligible, per-email denial and recipient delivery failure all acknowledge alike. */
  public void requestReset(String email) {
    String canonical = EmailAddress.canonical(email);
    try {
      delivery.issueAndDeliver(
          canonical, ActionTokenPurpose.RESET, () -> accounts.resetTarget(canonical).orElse(null));
    } catch (AuthenticationPreconditionException exception) {
      // Eligibility may change after selection. No recipient-specific outcome reaches HTTP.
      return;
    }
  }

  /** Hashes before locking, then consumes and mutates the bound account atomically. */
  public void complete(ActionTokenPurpose purpose, String secret, String password) {
    requireIndependentCompletion();
    var snapshot = accounts.forToken(purpose, secret);
    String verifier = prepare(password, snapshot.canonical());
    transactions.executeWithoutResult(
        status -> {
          accounts.lockUnchanged(snapshot);
          var consumed = tokens.consume(purpose, secret);
          if (!consumed.account().equals(snapshot.account())
              || consumed.securityVersion() != snapshot.version()) {
            throw new AuthenticationPreconditionException();
          }
          accounts.replace(snapshot, verifier, purpose == ActionTokenPurpose.INVITATION);
          tokens.invalidate(snapshot.account());
          if (purpose == ActionTokenPurpose.RESET) {
            sessions.removeAll(snapshot.account());
          }
        });
  }

  /** Current password proof and unchanged authority precede transactional revocation. */
  public void change(CurrentAuthority current, String oldPassword, String newPassword) {
    requireIndependentCompletion();
    var snapshot = accounts.account(current.account());
    if (snapshot.version() != current.version()) {
      throw new AuthenticationRequiredException();
    }
    if (!passwords.matches(oldPassword, snapshot.verifier())) {
      throw new AuthenticationFailedException();
    }
    String verifier = prepare(newPassword, snapshot.canonical());
    transactions.executeWithoutResult(
        status -> {
          accounts.lockUnchanged(snapshot);
          authority.requireCurrent(
              current.account(),
              current.sessionReference(),
              current.version(),
              current.instance(),
              current.scope(),
              current.mode());
          accounts.replace(snapshot, verifier, false);
          tokens.invalidate(snapshot.account());
          sessions.removeAll(snapshot.account());
        });
  }

  private static void requireIndependentCompletion() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Account completion requires independent preparation.");
    }
  }

  private String prepare(String password, String canonical) {
    if (!policy.acceptsNewPassword(password, canonical)) {
      throw new InvalidRequestException();
    }
    return passwords.encode(password);
  }
}
