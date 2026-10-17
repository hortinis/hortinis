package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.EmailAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Host-only sole-scope reservation; no account or scope can be supplied by a browser. */
public final class InvitationPersistence {
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Shares JDBC and UTC with the token issuance transaction. */
  public InvitationPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Atomically reserves only the fresh, empty, unowned designated sole scope. */
  public UUID provision(String email) {
    String canonical = EmailAddress.canonical(email);
    var installation = lockInstallation(true);
    var scopes =
        jdbc.sql(
                "SELECT synchronization_scope_id FROM technical_synchronization_scope "
                    + "WHERE server_instance_id = ? AND technical_default AND "
                    + "last_published_sequence = 0 FOR UPDATE")
            .param(installation)
            .query(UUID.class)
            .list();
    boolean empty =
        jdbc.sql(
                "SELECT (SELECT count(*) FROM technical_synchronization_scope) = 1 "
                    + "AND NOT EXISTS (SELECT 1 FROM authentication_account) "
                    + "AND (NOT EXISTS (SELECT 1 FROM spring_session) OR EXISTS "
                    + "(SELECT 1 FROM technical_server_installation WHERE singleton "
                    + "AND access_mode = 'single-user-auth')) "
                    + "AND NOT EXISTS (SELECT 1 FROM technical_record) "
                    + "AND NOT EXISTS (SELECT 1 FROM accepted_technical_record_operation) "
                    + "AND NOT EXISTS (SELECT 1 FROM technical_record_change) "
                    + "AND NOT EXISTS (SELECT 1 FROM technical_record_tombstone) "
                    + "AND NOT EXISTS (SELECT 1 FROM retired_technical_record_identifier) "
                    + "AND NOT EXISTS (SELECT 1 FROM technical_exchange_expectation)")
            .query(Boolean.class)
            .single();
    if (scopes.size() != 1 || !empty) {
      throw new AuthenticationPreconditionException();
    }
    jdbc.sql(
            "UPDATE technical_server_installation SET access_mode = "
                + "'single-user-auth' WHERE singleton")
        .update();
    return jdbc.sql(
            "INSERT INTO authentication_account (server_instance_id, synchronization_scope_id, "
                + "delivery_email, canonical_email, status, created_at) VALUES (?, "
                + "?, ?, ?, 'pending', ?) RETURNING account_id")
        .params(installation, scopes.getFirst(), email, canonical, Timestamp.from(clock.instant()))
        .query(UUID.class)
        .single();
  }

  /** Canonical targeting never changes the stored recipient, ownership or creation deadline. */
  public UUID pending(String email) {
    UUID installation = lockInstallation(false);
    return jdbc.sql(
            "SELECT a.account_id FROM authentication_account a "
                + "JOIN technical_synchronization_scope s ON "
                + "s.synchronization_scope_id = a.synchronization_scope_id "
                + "AND s.server_instance_id = a.server_instance_id AND s.technical_default "
                + "WHERE a.canonical_email = ? AND a.server_instance_id = ? AND "
                + "a.status = 'pending' "
                + "AND (SELECT count(*) FROM authentication_account) = 1 "
                + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1 FOR UPDATE OF a")
        .params(EmailAddress.canonical(email), installation)
        .query(UUID.class)
        .optional()
        .orElseThrow(AuthenticationPreconditionException::new);
  }

  /** Removes only a locked pending reservation and its cascading action tokens. */
  public void cancel(String email) {
    UUID account = pending(email);
    jdbc.sql("DELETE FROM authentication_account WHERE account_id = ?").param(account).update();
  }

  private UUID lockInstallation(boolean fresh) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Invitation reservation requires a writable transaction.");
    }
    return jdbc.sql(
            "SELECT server_instance_id FROM technical_server_installation WHERE singleton "
                + "AND (access_mode = 'single-user-auth' OR (? AND access_mode IS "
                + "NULL)) FOR UPDATE")
        .param(fresh)
        .query(UUID.class)
        .optional()
        .orElseThrow(AuthenticationPreconditionException::new);
  }
}
