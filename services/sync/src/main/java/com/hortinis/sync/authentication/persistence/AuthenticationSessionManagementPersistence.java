package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Authorized minimized session listing, explicit interaction and same-account targeted revocation.
 */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionManagementPersistence {
  private static final int MAX_FULL_SESSIONS = 5;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationSessionRemovalPersistence removal;

  /** The caller holds the same ordered account/parent locks used by protected synchronization. */
  public AuthenticationSessionManagementPersistence(
      JdbcClient jdbc,
      Clock clock,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationSessionRemovalPersistence removal) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.sessions = sessions;
    this.removal = removal;
  }

  /**
   * Return only live owned full sessions, without location, network or browser fingerprint fields.
   */
  public List<SessionSummary> list(AuthenticationSession parent) {
    sessions.requireFull(parent);
    Instant now = clock.instant();
    var result =
        jdbc.sql(
                "SELECT logout_target, created_at, expires_at, session_reference = ? "
                    + "FROM authentication_session_authority WHERE account_id = ? AND "
                    + "authority_epoch = ? "
                    + "AND state = 'full' AND created_at <= ? AND expires_at > ? "
                    + "AND full_login_at > ? AND last_interaction_at <= ? AND "
                    + "last_interaction_at > ? "
                    + "AND both_factors_at <= ? AND both_factors_at >= full_login_at "
                    + "ORDER BY created_at, session_reference LIMIT 6")
            .params(
                parent.reference(),
                parent.account(),
                parent.epoch(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.minus(AuthenticationLifetime.FULL_ABSOLUTE)),
                Timestamp.from(now),
                Timestamp.from(now.minus(AuthenticationLifetime.FULL_IDLE)),
                Timestamp.from(now))
            .query(
                (row, number) ->
                    new SessionSummary(
                        row.getString(1),
                        row.getTimestamp(2).toInstant(),
                        row.getTimestamp(3).toInstant(),
                        row.getBoolean(4)))
            .list();
    if (result.size() > MAX_FULL_SESSIONS) {
      throw new AuthenticationFailedException();
    }
    return result;
  }

  /** Qualifying activity extends idle expiry only; absolute login/factor times are unchanged. */
  public void activity(AuthenticationSession parent) {
    sessions.requireFull(parent);
    Instant now = clock.instant();
    if (!AuthenticationLifetime.fullIsCurrent(parent.loginAt(), parent.interactionAt(), now)) {
      throw new AuthenticationFailedException();
    }
    jdbc.sql(
            "UPDATE authentication_session_authority SET last_interaction_at "
                + "= ? WHERE session_reference = ?")
        .params(Timestamp.from(now), parent.reference())
        .update();
  }

  /**
   * Unknown/foreign targets disclose nothing and cannot remove an unrelated account's authority.
   */
  public boolean revoke(AuthenticationSession parent, String target) {
    sessions.requireRecent(parent);
    var reference =
        jdbc.sql(
                "SELECT session_reference FROM authentication_session_authority "
                    + "WHERE account_id = ? AND logout_target = ? FOR UPDATE")
            .params(parent.account(), target)
            .query(java.util.UUID.class)
            .optional();
    if (reference.isEmpty()) {
      return false;
    }
    removal.remove(reference.orElseThrow());
    return reference.orElseThrow().equals(parent.reference());
  }

  /**
   * Non-authorizing target and coarse listing fields; no native session secret is ever projected.
   */
  public record SessionSummary(
      String target, Instant createdAt, Instant absoluteExpiresAt, boolean current) {
    @Override
    public String toString() {
      return "SessionSummary[redacted]";
    }
  }
}
