package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Atomic factor replacement/epoch invalidation; synchronization history and ownership stay intact.
 */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationAccountSecurityPersistence {
  private final JdbcClient jdbc;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationSessionRemovalPersistence removal;

  /** Uses concrete dedicated persistence components in the caller's publication transaction. */
  public AuthenticationAccountSecurityPersistence(
      JdbcClient jdbc,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionRemovalPersistence removal) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.removal = removal;
  }

  /** Caller has proved the current password and current recent full authority. */
  public void password(
      AuthenticationAccountPersistence.AccountSnapshot observed, String replacement) {
    var current = accounts.requireUnchanged(observed);
    jdbc.sql("UPDATE authentication_account SET password_verifier = ? WHERE account_id = ?")
        .params(replacement, current.account())
        .update();
  }

  /**
   * Revoke all old full/limited authority and incompatible tickets, then invalidate this transport.
   */
  public void invalidate(
      AuthenticationAccountPersistence.AccountSnapshot observed,
      AuthenticationSessionTransport transport) {
    // Password replacement may already have changed the verifier in this transaction; epoch,
    // instance/scope and current account status remain independently constrained here.
    long next;
    try {
      next = Math.addExact(observed.epoch(), 1);
    } catch (ArithmeticException exception) {
      throw new AuthenticationFailedException();
    }
    boolean advanced =
        jdbc.sql(
                    "UPDATE authentication_account SET authority_epoch = ? "
                        + "WHERE account_id = ? AND authority_epoch = ? AND status = 'active' "
                        + "AND server_instance_id = ? AND synchronization_scope_id = ?")
                .params(
                    next,
                    observed.account(),
                    observed.epoch(),
                    observed.instance(),
                    observed.scope())
                .update()
            == 1;
    if (!advanced) {
      throw new AuthenticationFailedException();
    }
    removal.removeAll(observed.account());
    jdbc.sql("DELETE FROM authentication_operator_ticket WHERE account_id = ?")
        .param(observed.account())
        .update();
    transport.invalidate();
  }
}
