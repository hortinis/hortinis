package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Current identity lookup and ordered transaction locks; saved session objects are never authority.
 */
@Repository
@SynchronizationPersistenceEnabled
public class CurrentAuthenticationAuthorityPersistence {
  private static final String ACTIVE = "active";
  private static final String FULL = "full";
  private static final String SINGLE_USER_AUTH = "single-user-auth";
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Uses the shared JDBC transaction manager and trusted clock, not transport or browser time. */
  public CurrentAuthenticationAuthorityPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Resolves minimized metadata only from the current opaque cookie's verifier and row reference.
   */
  public Optional<FullAuthority> findFull(String sessionVerifier, UUID expectedReference) {
    if (sessionVerifier == null
        || !sessionVerifier.matches("[a-f0-9]{64}")
        || expectedReference == null) {
      return Optional.empty();
    }
    return jdbc.sql(
            "SELECT s.session_reference, a.account_id, a.authority_epoch, "
                + "a.server_instance_id, a.synchronization_scope_id, i.access_mode, "
                + "s.full_login_at, "
                + "s.both_factors_at, s.expires_at, s.created_at "
                + "FROM authentication_session_authority s JOIN authentication_account a "
                + "ON a.account_id = s.account_id JOIN technical_server_installation i "
                + "ON i.server_instance_id = a.server_instance_id "
                + "WHERE s.session_verifier = ? AND s.session_reference = ? AND s.state = 'full' "
                + "AND a.status = 'active' AND s.authority_epoch = a.authority_epoch "
                + "AND i.access_mode = 'single-user-auth'")
        .params(sessionVerifier, expectedReference)
        .query(
            (row, number) ->
                new FullAuthority(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getLong(3),
                    row.getObject(4, UUID.class),
                    row.getObject(5, UUID.class),
                    row.getString(6),
                    row.getTimestamp(7).toInstant(),
                    row.getTimestamp(8).toInstant(),
                    row.getTimestamp(9).toInstant(),
                    row.getTimestamp(10).toInstant()))
        .optional()
        .filter(authority -> authority.isCurrent(clock.instant()));
  }

  /**
   * Locks account first and session second, retaining both through the caller's content
   * transaction.
   */
  public void requireCurrent(
      UUID accountId, UUID sessionReference, long epoch, UUID instance, UUID scope, String mode) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Authentication authority requires a protected transaction.");
    }
    AccountState account =
        jdbc.sql(
                "SELECT status, authority_epoch, server_instance_id, "
                    + "synchronization_scope_id FROM authentication_account "
                    + "WHERE account_id = ? FOR SHARE")
            .param(accountId)
            .query(
                (row, number) ->
                    new AccountState(
                        row.getString(1),
                        row.getLong(2),
                        row.getObject(3, UUID.class),
                        row.getObject(4, UUID.class)))
            .optional()
            .orElseThrow(AuthenticationRequiredException::new);
    if (!ACTIVE.equals(account.status())
        || account.epoch() != epoch
        || !account.instance().equals(instance)
        || !account.scope().equals(scope)
        || !SINGLE_USER_AUTH.equals(mode)) {
      throw new AuthenticationRequiredException();
    }
    AuthenticationSession session =
        jdbc.sql(
                "SELECT "
                    + AuthenticationSessionRows.COLUMNS
                    + " FROM authentication_session_authority "
                    + "WHERE session_reference = ? FOR SHARE")
            .param(sessionReference)
            .query(AuthenticationSessionRows::read)
            .optional()
            .orElseThrow(AuthenticationRequiredException::new);
    Instant now = clock.instant();
    if (!FULL.equals(session.state())
        || !accountId.equals(session.account())
        || session.epoch() != epoch
        || !session.isUnexpired(now)
        || !session.hasCurrentFactors(now)) {
      throw new AuthenticationRequiredException();
    }
    String committedMode =
        jdbc.sql(
                "SELECT access_mode FROM technical_server_installation "
                    + "WHERE singleton AND server_instance_id = ?")
            .param(instance)
            .query(String.class)
            .optional()
            .orElseThrow(AuthenticationRequiredException::new);
    if (!mode.equals(committedMode)) {
      throw new AuthenticationRequiredException();
    }
  }

  /** No secrets or identity representations are exposed to diagnostics. */
  public record FullAuthority(
      UUID sessionReference,
      UUID account,
      long epoch,
      UUID instance,
      UUID scope,
      String mode,
      Instant loginAt,
      Instant bothFactorsAt,
      Instant expiresAt,
      Instant createdAt) {
    private boolean isCurrent(Instant now) {
      return AuthenticationLifetime.isUnexpired(createdAt, expiresAt, now)
          && AuthenticationLifetime.fullIsCurrent(loginAt, bothFactorsAt, now);
    }

    @Override
    public String toString() {
      return "FullAuthority[redacted]";
    }
  }

  private record AccountState(String status, long epoch, UUID instance, UUID scope) {
    @Override
    public String toString() {
      return "AccountState[redacted]";
    }
  }
}
