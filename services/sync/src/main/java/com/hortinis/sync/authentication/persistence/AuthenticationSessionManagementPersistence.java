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
  private final JdbcClient jdbc;
  private final Clock clock;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationSessionRemovalPersistence removal;
  private final AuthenticationNativeSessions nativeSessions;

  /** The caller holds the same ordered account/parent locks used by protected synchronization. */
  public AuthenticationSessionManagementPersistence(
      JdbcClient jdbc,
      Clock clock,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationSessionRemovalPersistence removal,
      AuthenticationNativeSessions nativeSessions) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.sessions = sessions;
    this.removal = removal;
    this.nativeSessions = nativeSessions;
  }

  /**
   * Return only live owned full sessions, without location, network or browser fingerprint fields.
   */
  public List<SessionSummary> list(AuthenticationSession parent) {
    sessions.requireFull(parent);
    return nativeSessions.list(parent.account(), parent.epoch()).stream()
        .map(
            session ->
                new SessionSummary(
                    session.target(),
                    session.createdAt(),
                    session.expiresAt(),
                    session.reference().equals(parent.reference())))
        .toList();
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
    var selected =
        nativeSessions.list(parent.account(), parent.epoch()).stream()
            .filter(session -> session.target().equals(target))
            .findFirst();
    if (selected.isEmpty()) {
      return false;
    }
    var reference = selected.orElseThrow().reference();
    removal.remove(reference);
    return reference.equals(parent.reference());
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
