package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Exact controlled host ticket claim and atomic both-factor replacement; no garden authority. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationOperatorRecoveryPersistence {
  private static final int ONE_ROW = 1;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationAccountSecurityPersistence security;

  /** Shares ordered publication locks and the authoritative account invalidation component. */
  public AuthenticationOperatorRecoveryPersistence(
      JdbcClient jdbc,
      Clock clock,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationAccountPersistence accounts,
      AuthenticationAccountSecurityPersistence security) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.sessions = sessions;
    this.accounts = accounts;
    this.security = security;
  }

  /**
   * Non-authorizing snapshot for new-password policy/crypto, rechecked when the claim publishes.
   */
  public AuthenticationAccountPersistence.AccountSnapshot target(String ticketSecret) {
    if (!AuthenticationSecrets.isRandomValue(ticketSecret)) {
      throw new AuthenticationFailedException();
    }
    UUID target =
        jdbc.sql(
                "SELECT account_id FROM authentication_operator_ticket "
                    + "WHERE purpose = 'operator' AND secret_verifier = ?")
            .param(AuthenticationSecrets.digest(ticketSecret))
            .query(UUID.class)
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    return accounts.findAccount(target);
  }

  /** Claim transfer precedes predecessor retirement so its cascading FK cannot erase the ticket. */
  public UUID begin(
      AuthenticationSessionTransport transport,
      AuthenticationAccountPersistence.AccountSnapshot observed,
      String ticketSecret,
      String hash) {
    var parent =
        sessions.lock(
            transport.observe().orElseThrow(AuthenticationFailedException::new),
            observed.account());
    var account = accounts.requireUnchanged(observed);
    var ticket = currentTicket(account);
    boolean retry =
        "recovery".equals(parent.state())
            && "operator".equals(parent.recoveryKind())
            && parent.reference().equals(ticket.claimed());
    if (ticket.claimed() != null && !retry
        || !AuthenticationSecrets.matchesDigest(ticketSecret, ticket.verifier())) {
      throw new AuthenticationFailedException();
    }
    UUID reference = sessions.stageOperator(parent, account, hash, ticket.expiresAt(), transport);
    if (jdbc.sql(
                "UPDATE authentication_operator_ticket SET claimed_session_reference = ? "
                    + "WHERE ticket_reference = ? AND (claimed_session_reference IS NULL "
                    + "OR claimed_session_reference = ?)")
            .params(reference, ticket.reference(), parent.reference())
            .update()
        != ONE_ROW) {
      throw new AuthenticationFailedException();
    }
    sessions.retire(parent);
    return reference;
  }

  /** Called after current nonce/purpose/origin/kind verification in the same transaction. */
  public boolean complete(
      AuthenticationSessionPublicationPersistence.Parent parent,
      AuthenticationAccountPersistence.AccountSnapshot observed,
      BoundWebAuthnOperations.VerifiedCredential proof,
      AuthenticationSessionTransport transport) {
    AuthenticationSessionPublicationPersistence.requireState(parent, "recovery");
    var account = accounts.requireUnchanged(observed);
    var ticket = currentTicket(account);
    if (!"operator".equals(parent.recoveryKind())
        || parent.stagedPassword() == null
        || parent.stagedHandle() == null
        || parent.bothFactorsAt() != null
        || !parent.reference().equals(ticket.claimed())
        || proof.kind() != BoundWebAuthnOperations.ProofKind.REGISTRATION
        || !MessageDigest.isEqual(parent.stagedHandle(), account.userHandle())
        || !MessageDigest.isEqual(
            parent.stagedHandle(), proof.credential().getUserEntityUserId().getBytes())) {
      throw new AuthenticationFailedException();
    }
    accounts.replaceAllCredentials(account, proof);
    security.password(account, parent.stagedPassword());
    jdbc.sql("DELETE FROM authentication_recovery_code WHERE account_id = ?")
        .param(account.account())
        .update();
    if (jdbc.sql("DELETE FROM authentication_operator_ticket WHERE ticket_reference = ?")
            .param(ticket.reference())
            .update()
        != ONE_ROW) {
      throw new AuthenticationFailedException();
    }
    security.invalidate(account, transport);
    return true;
  }

  private Ticket currentTicket(AuthenticationAccountPersistence.AccountSnapshot account) {
    var ticket =
        jdbc.sql(
                "SELECT ticket_reference, secret_verifier, claimed_session_reference, "
                    + "created_at, expires_at FROM authentication_operator_ticket "
                    + "WHERE purpose = 'operator' "
                    + "AND account_id = ? AND target_id = ? AND server_instance_id = ? "
                    + "AND access_mode = 'single-user-auth' FOR UPDATE")
            .params(account.account(), account.account(), account.instance())
            .query(
                (row, number) ->
                    new Ticket(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getObject(3, UUID.class),
                        row.getTimestamp(4).toInstant(),
                        row.getTimestamp(5).toInstant()))
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    Instant now = clock.instant();
    if (now.isBefore(ticket.createdAt()) || !now.isBefore(ticket.expiresAt())) {
      throw new AuthenticationFailedException();
    }
    return ticket;
  }

  private record Ticket(
      UUID reference, String verifier, UUID claimed, Instant createdAt, Instant expiresAt) {
    @Override
    public String toString() {
      return "OperatorRecoveryTicket[redacted]";
    }
  }
}
