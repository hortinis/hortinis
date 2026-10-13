package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ticket claim and atomic sole-account activation; setup never creates full data authority. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSetupPersistence {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationRecoveryCodePersistence codes;

  /** Dedicated components share the caller's explicit setup transaction. */
  public AuthenticationSetupPersistence(
      JdbcClient jdbc,
      Clock clock,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationAccountPersistence accounts,
      AuthenticationRecoveryCodePersistence codes) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.sessions = sessions;
    this.accounts = accounts;
    this.codes = codes;
  }

  /** Claims the current ticket once; its same bounded owner may replace interrupted options. */
  public UUID begin(
      AuthenticationSessionTransport transport, String ticketSecret, String login, String hash) {
    var parent = sessions.lock(transport.observe().orElseThrow(AuthenticationFailedException::new));
    if (!"anonymous".equals(parent.state()) && !"setup".equals(parent.state())) {
      throw new AuthenticationFailedException();
    }
    UUID installation = installation();
    freshScope(installation);
    Ticket ticket = currentTicket(installation);
    boolean retry = "setup".equals(parent.state()) && parent.reference().equals(ticket.claimed());
    if (ticket.claimed() != null && !retry
        || !AuthenticationSecrets.matchesDigest(ticketSecret, ticket.verifier())) {
      throw new AuthenticationFailedException();
    }
    var staged =
        new AuthenticationSessionPublicationPersistence.SetupMaterial(
            login,
            hash,
            retry
                ? parent.stagedHandle()
                : java.util.Base64.getUrlDecoder().decode(AuthenticationSecrets.randomValue()),
            ticket.expiresAt());
    UUID reference = sessions.stageSetup(parent, staged, transport);
    boolean claimed =
        jdbc.sql(
                    "UPDATE authentication_operator_ticket SET "
                        + "claimed_session_reference = ? "
                        + "WHERE ticket_reference = ? AND (claimed_session_reference IS "
                        + "NULL OR claimed_session_reference = ?)")
                .params(reference, ticket.reference(), parent.reference())
                .update()
            == 1;
    if (!claimed) {
      throw new AuthenticationFailedException();
    }
    sessions.retire(parent);
    return reference;
  }

  /**
   * Called only after nonce/origin/kind/parent binding is checked in the same publication
   * transaction.
   */
  public AuthenticationRecoveryCodePersistence.IssuedCodes complete(
      AuthenticationSession parent,
      BoundWebAuthnOperations.VerifiedCredential proof,
      AuthenticationSessionTransport transport) {
    AuthenticationSessionPublicationPersistence.requireState(parent, "setup");
    UUID installation = installation();
    Ticket ticket = currentTicket(installation);
    if (!parent.reference().equals(ticket.claimed())
        || proof.kind() != BoundWebAuthnOperations.ProofKind.REGISTRATION
        || !MessageDigest.isEqual(
            parent.stagedHandle(), proof.credential().getUserEntityUserId().getBytes())) {
      throw new AuthenticationFailedException();
    }
    UUID scope = freshScope(installation);
    UUID account = accounts.activate(installation, scope, parent);
    accounts.firstCredential(account, proof);
    final var issued = codes.initializeForSetup(account);
    jdbc.sql("DELETE FROM authentication_operator_ticket WHERE ticket_reference = ?")
        .param(ticket.reference())
        .update();
    sessions.retire(parent);
    transport.invalidate();
    return issued;
  }

  private UUID installation() {
    return jdbc.sql(
            "SELECT server_instance_id FROM technical_server_installation "
                + "WHERE singleton AND access_mode = 'single-user-auth'")
        .query(UUID.class)
        .optional()
        .orElseThrow(AuthenticationFailedException::new);
  }

  private Ticket currentTicket(UUID installation) {
    Ticket result =
        jdbc.sql(
                "SELECT ticket_reference, secret_verifier, claimed_session_reference, "
                    + "created_at, expires_at FROM authentication_operator_ticket WHERE "
                    + "purpose = 'setup' "
                    + "AND target_id = ? AND server_instance_id = ? AND access_mode = "
                    + "'single-user-auth' "
                    + "AND account_id IS NULL FOR UPDATE")
            .params(installation, installation)
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
    if (now.isBefore(result.createdAt()) || !now.isBefore(result.expiresAt())) {
      throw new AuthenticationFailedException();
    }
    return result;
  }

  private UUID freshScope(UUID installation) {
    var scopes =
        jdbc.sql(
                "SELECT synchronization_scope_id FROM technical_synchronization_scope "
                    + "WHERE server_instance_id = ? AND technical_default AND "
                    + "last_published_sequence = 0 "
                    + "FOR UPDATE")
            .param(installation)
            .query(UUID.class)
            .list();
    boolean empty =
        jdbc.sql(
                "SELECT NOT (EXISTS (SELECT 1 FROM authentication_account) "
                    + "OR EXISTS (SELECT 1 FROM technical_record) OR EXISTS (SELECT 1 "
                    + "FROM accepted_technical_record_operation) "
                    + "OR EXISTS (SELECT 1 FROM technical_record_change) OR EXISTS "
                    + "(SELECT 1 FROM technical_record_tombstone) "
                    + "OR EXISTS (SELECT 1 FROM retired_technical_record_identifier) "
                    + "OR EXISTS (SELECT 1 FROM technical_exchange_expectation)) "
                    + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1")
            .query(Boolean.class)
            .single();
    if (!empty || scopes.size() != 1) {
      throw new AuthenticationFailedException();
    }
    return scopes.getFirst();
  }

  private record Ticket(
      UUID reference, String verifier, UUID claimed, Instant createdAt, Instant expiresAt) {
    @Override
    public String toString() {
      return "SetupTicket[redacted]";
    }
  }
}
