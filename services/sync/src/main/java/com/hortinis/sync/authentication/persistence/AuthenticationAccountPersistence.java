package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Password lookup returns only eligible sole-account snapshots, never account authority. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationAccountPersistence {
  private final JdbcClient jdbc;

  /** Uses application JDBC; password work happens outside database transactions. */
  public AuthenticationAccountPersistence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Ineligible and unknown accounts use the same native provider dummy-verifier path. */
  public Optional<AccountSnapshot> findLogin(String email) {
    return jdbc.sql(
            "SELECT a.account_id, a.security_version, a.password_verifier "
                + "FROM authentication_account a "
                + "JOIN technical_server_installation i ON i.server_instance_id"
                + " = a.server_instance_id "
                + "JOIN technical_synchronization_scope s "
                + "ON s.synchronization_scope_id = a.synchronization_scope_id "
                + "AND s.server_instance_id = a.server_instance_id AND s.technical_default "
                + "WHERE a.canonical_email = ? AND a.status = 'active' AND "
                + "a.verified_at IS NOT NULL "
                + "AND a.password_verifier IS NOT NULL AND i.access_mode = 'single-user-auth' "
                + "AND (SELECT count(*) FROM authentication_account) = 1 "
                + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1 ")
        .param(EmailAddress.canonical(email))
        .query(
            (row, number) ->
                new AccountSnapshot(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)))
        .optional();
  }

  /** Transient provider input; neither this snapshot nor its verifier enters a saved principal. */
  public record AccountSnapshot(UUID account, long version, String passwordVerifier) {
    @Override
    public String toString() {
      return "AccountSnapshot[redacted]";
    }
  }
}
