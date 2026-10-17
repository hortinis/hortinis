package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current committed eligibility/ownership checks; a saved context alone never grants access. */
@Repository
@SynchronizationPersistenceEnabled
public class CurrentAuthenticationAuthorityPersistence {
  private final JdbcClient jdbc;
  private final AuthenticationNativeSessions sessions;

  /** Shares JDBC and native rows with synchronization and account mutation transactions. */
  public CurrentAuthenticationAuthorityPersistence(
      JdbcClient jdbc, AuthenticationNativeSessions sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  /** Resolves minimized current metadata from the native cookie and erased provider identity. */
  public Optional<CurrentAuthority> find(String identifier, AuthenticationIdentity identity) {
    return sessions
        .find(identifier, identity)
        .flatMap(
            reference ->
                account(identity, false)
                    .map(
                        value ->
                            new CurrentAuthority(
                                reference,
                                identity.account(),
                                identity.version(),
                                value.instance(),
                                value.scope(),
                                value.mode())));
  }

  /** Account first, session second, scope third in the caller's protected transaction. */
  public void requireCurrent(
      UUID accountId, UUID sessionReference, long version, UUID instance, UUID scope, String mode) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Authentication authority requires a protected transaction.");
    }
    var identity = new AuthenticationIdentity(accountId, version);
    var account = account(identity, true).orElseThrow(AuthenticationRequiredException::new);
    if (!account.instance().equals(instance)
        || !account.scope().equals(scope)
        || !account.mode().equals(mode)) {
      throw new AuthenticationRequiredException();
    }
    sessions.requireCurrent(sessionReference, identity);
  }

  private Optional<AccountAuthority> account(AuthenticationIdentity identity, boolean lock) {
    return jdbc.sql(
            "SELECT a.server_instance_id, a.synchronization_scope_id, i.access_mode "
                + "FROM authentication_account a "
                + "JOIN technical_server_installation i ON i.server_instance_id"
                + " = a.server_instance_id "
                + "JOIN technical_synchronization_scope s ON "
                + "s.server_instance_id = a.server_instance_id "
                + "AND s.synchronization_scope_id = a.synchronization_scope_id "
                + "AND s.technical_default "
                + "WHERE a.account_id = ? AND a.security_version = ? AND a.status = 'active' "
                + "AND a.verified_at IS NOT NULL AND a.password_verifier IS NOT NULL "
                + "AND i.access_mode = 'single-user-auth' "
                + "AND (SELECT count(*) FROM authentication_account) = 1 "
                + "AND (SELECT count(*) FROM technical_synchronization_scope) = 1 "
                + (lock ? " FOR SHARE OF a" : ""))
        .params(identity.account(), identity.version())
        .query(
            (row, number) ->
                new AccountAuthority(
                    row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3)))
        .optional();
  }

  /** Native primary row identity is internal, not an API logout target or credential. */
  public record CurrentAuthority(
      UUID sessionReference, UUID account, long version, UUID instance, UUID scope, String mode) {
    @Override
    public String toString() {
      return "CurrentAuthority[redacted]";
    }
  }

  private record AccountAuthority(UUID instance, UUID scope, String mode) {}
}
