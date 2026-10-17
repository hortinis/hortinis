package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Host-only ticket publication; there is no HTTP entry point or factor/ownership bypass here. */
public class AuthenticationOperatorTicketPersistence {
  private static final String AUTH_MODE = "single-user-auth";
  private final JdbcClient jdbc;
  private final Clock clock;

  /** All host transactions share the application datasource and trusted clock. */
  public AuthenticationOperatorTicketPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Setup binds to the current installation and its sole empty unowned scope. */
  public UUID issueSetup(String verifier) {
    Installation selected = lockInstallation(true);
    UUID installation = selected.instance();
    if (jdbc.sql("SELECT count(*) FROM authentication_account").query(Integer.class).single()
        != 0) {
      throw new AuthenticationFailedException();
    }
    var scopes =
        jdbc.sql(
                "SELECT synchronization_scope_id, server_instance_id, technical_default, "
                    + "last_published_sequence FROM technical_synchronization_scope "
                    + "ORDER BY synchronization_scope_id FOR UPDATE")
            .query(
                (row, number) ->
                    new Scope(row.getObject(2, UUID.class), row.getBoolean(3), row.getLong(4)))
            .list();
    if (scopes.size() != 1
        || !scopes.getFirst().instance().equals(installation)
        || !scopes.getFirst().designated()
        || scopes.getFirst().published() != 0
        || !technicalStateIsEmpty()) {
      throw new AuthenticationFailedException();
    }
    if (selected.mode() == null) {
      requireUnconfiguredAuthenticationState();
      jdbc.sql("UPDATE technical_server_installation SET access_mode = ? WHERE singleton")
          .param(AUTH_MODE)
          .update();
    }
    return publish("setup", installation, installation, null, verifier);
  }

  /** Controlled host recovery targets only the sole active account with its existing ownership. */
  public UUID issueRecovery(UUID account, String verifier) {
    UUID installation = lockInstallation(false).instance();
    if (account == null
        || jdbc.sql("SELECT count(*) FROM authentication_account").query(Integer.class).single()
            != 1) {
      throw new AuthenticationFailedException();
    }
    UUID scope =
        jdbc.sql(
                "SELECT synchronization_scope_id FROM authentication_account "
                    + "WHERE account_id = ? AND status = 'active' "
                    + "AND server_instance_id = ? FOR UPDATE")
            .params(account, installation)
            .query(UUID.class)
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    Boolean designated =
        jdbc.sql(
                "SELECT technical_default FROM technical_synchronization_scope "
                    + "WHERE synchronization_scope_id = ? AND server_instance_id = ? FOR UPDATE")
            .params(scope, installation)
            .query(Boolean.class)
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    if (!Boolean.TRUE.equals(designated)
        || jdbc.sql("SELECT count(*) FROM technical_synchronization_scope")
                .query(Integer.class)
                .single()
            != 1) {
      throw new AuthenticationFailedException();
    }
    return publish("operator", account, installation, account, verifier);
  }

  /** Called with the installation lock held through protected file reconciliation. */
  public Set<UUID> reconcileReferences() {
    Installation selected = lockInstallation(true);
    UUID installation = selected.instance();
    if (selected.mode() == null) {
      requireUnconfiguredAuthenticationState();
      return Set.of();
    }
    // S9 has one account; preserve the same ordering for malformed/extra rows without granting
    // access.
    jdbc.sql("SELECT account_id FROM authentication_account ORDER BY account_id FOR UPDATE")
        .query(UUID.class)
        .list();
    Instant now = clock.instant();
    jdbc.sql(
            "DELETE FROM authentication_operator_ticket t WHERE expires_at <= ? "
                + "OR created_at > ? OR server_instance_id <> ? OR access_mode <> ? "
                + "OR (account_id IS NOT NULL AND NOT EXISTS (SELECT 1 "
                + "FROM authentication_account a "
                + "WHERE a.account_id = t.account_id AND a.status = 'active'))")
        .params(Timestamp.from(now), Timestamp.from(now), installation, AUTH_MODE)
        .update();
    return new HashSet<>(
        jdbc.sql("SELECT ticket_reference FROM authentication_operator_ticket")
            .query(UUID.class)
            .list());
  }

  private Installation lockInstallation(boolean allowUnconfigured) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Controlled ticket publication requires a transaction.");
    }
    return jdbc.sql(
            "SELECT server_instance_id, access_mode FROM technical_server_installation "
                + "WHERE singleton AND (access_mode = ? OR (? AND access_mode IS NULL)) FOR UPDATE")
        .params(AUTH_MODE, allowUnconfigured)
        .query((row, number) -> new Installation(row.getObject(1, UUID.class), row.getString(2)))
        .optional()
        .orElseThrow(AuthenticationFailedException::new);
  }

  private void requireUnconfiguredAuthenticationState() {
    boolean empty =
        jdbc.sql(
                "SELECT NOT (EXISTS (SELECT 1 FROM authentication_account) "
                    + "OR EXISTS (SELECT 1 FROM spring_session) "
                    + "OR EXISTS (SELECT 1 FROM authentication_operator_ticket))")
            .query(Boolean.class)
            .single();
    if (!empty || !technicalStateIsEmpty()) {
      throw new AuthenticationFailedException();
    }
  }

  private boolean technicalStateIsEmpty() {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT NOT (EXISTS (SELECT 1 FROM technical_record) "
                    + "OR EXISTS (SELECT 1 FROM accepted_technical_record_operation) "
                    + "OR EXISTS (SELECT 1 FROM technical_record_change) "
                    + "OR EXISTS (SELECT 1 FROM technical_record_tombstone) "
                    + "OR EXISTS (SELECT 1 FROM retired_technical_record_identifier) "
                    + "OR EXISTS (SELECT 1 FROM technical_exchange_expectation))")
            .query(Boolean.class)
            .single());
  }

  private UUID publish(
      String purpose, UUID target, UUID installation, UUID account, String verifier) {
    if (verifier == null || !verifier.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid controlled ticket verifier.");
    }
    UUID reference = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.sql(
            "INSERT INTO authentication_operator_ticket "
                + "(ticket_reference, secret_verifier, purpose, target_id, server_instance_id, "
                + "access_mode, account_id, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (purpose, target_id) DO UPDATE SET "
                + "ticket_reference = EXCLUDED.ticket_reference, "
                + "secret_verifier = EXCLUDED.secret_verifier, "
                + "server_instance_id = EXCLUDED.server_instance_id, "
                + "access_mode = EXCLUDED.access_mode, account_id = EXCLUDED.account_id, "
                + "created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at, "
                + "claimed_session_reference = NULL")
        .params(
            reference,
            verifier,
            purpose,
            target,
            installation,
            AUTH_MODE,
            account,
            Timestamp.from(now),
            Timestamp.from(now.plus(AuthenticationLifetime.TICKET_ABSOLUTE)))
        .update();
    return reference;
  }

  private record Installation(UUID instance, String mode) {
    @Override
    public String toString() {
      return "OperatorInstallation[redacted]";
    }
  }

  private record Scope(UUID instance, boolean designated, long published) {
    @Override
    public String toString() {
      return "Scope[redacted]";
    }
  }
}
