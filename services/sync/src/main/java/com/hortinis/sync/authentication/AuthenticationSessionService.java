package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionRemovalPersistence;
import java.time.Instant;
import java.util.List;

/** Native session management and target-fenced logout. */
public final class AuthenticationSessionService {
  private final AuthenticationPublication publication;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationNativeSessions nativeSessions;
  private final AuthenticationSessionRemovalPersistence removal;

  /** Request transport and current persistent authority remain distinct boundaries. */
  public AuthenticationSessionService(
      AuthenticationPublication publication,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationNativeSessions nativeSessions,
      AuthenticationSessionRemovalPersistence removal) {
    this.publication = publication;
    this.sessions = sessions;
    this.nativeSessions = nativeSessions;
    this.removal = removal;
  }

  /** Listing requires full authority; request activity follows Spring Session policy. */
  public List<SessionSummary> list(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          var parent = sessions.lock(observe(transport));
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
        });
  }

  /** Compatibility acknowledgement; the native session filter owns request activity. */
  public boolean activity(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          sessions.requireFull(sessions.lock(observe(transport)));
          return true;
        });
  }

  /** Same-account recent authority may revoke itself or another target. */
  public boolean revoke(AuthenticationSessionTransport transport, String target) {
    return publication.attempt(
        Event.SESSION_REVOKE,
        () ->
            publication.complete(
                Event.SESSION_REVOKE,
                () -> {
                  var parent = sessions.lock(observe(transport));
                  sessions.requireRecent(parent);
                  nativeSessions.list(parent.account(), parent.epoch()).stream()
                      .filter(session -> session.target().equals(target))
                      .findFirst()
                      .ifPresent(
                          session -> {
                            removal.remove(session.reference());
                            if (session.reference().equals(parent.reference())) {
                              transport.invalidate();
                            }
                          });
                  return true;
                }));
  }

  /** A late old target cannot log out a newer current full session, even for the same account. */
  public boolean logout(AuthenticationSessionTransport transport, String originalTarget) {
    return publication.attempt(
        Event.LOGOUT,
        () ->
            publication.complete(
                Event.LOGOUT,
                () -> {
                  if (!AuthenticationSecrets.isRandomValue(originalTarget)) {
                    throw new AuthenticationFailedException();
                  }
                  var observation = transport.observe();
                  var locked = sessions.lockForLogout(observation.orElse(null));
                  if (locked.isPresent()) {
                    var parent = locked.orElseThrow();
                    if (sessions.currentFull(parent)
                        && !parent.logoutTarget().equals(originalTarget)) {
                      throw new AuthenticationPreconditionException();
                    }
                    removal.remove(parent.reference());
                  } else if (observation.isPresent()) {
                    removal.remove(observation.orElseThrow().reference().value());
                  }
                  transport.invalidate();
                  return true;
                }));
  }

  /** Coarse session metadata without cookie identifiers or browser/network fingerprints. */
  public record SessionSummary(
      String target, Instant createdAt, Instant absoluteExpiresAt, boolean current) {
    @Override
    public String toString() {
      return "SessionSummary[redacted]";
    }
  }

  private static AuthenticationSessionTransport.Observation observe(
      AuthenticationSessionTransport transport) {
    return transport.observe().orElseThrow(AuthenticationFailedException::new);
  }
}
