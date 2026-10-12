package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionManagementPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionRemovalPersistence;
import java.util.List;

/** Current-session management and target-fenced logout; no workflow emits a replacement cookie. */
public final class AuthenticationSessionService {
  private final AuthenticationPublication publication;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationSessionManagementPersistence management;
  private final AuthenticationSessionRemovalPersistence removal;

  /** Request transport and current persistent authority remain distinct boundaries. */
  public AuthenticationSessionService(
      AuthenticationPublication publication,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationSessionManagementPersistence management,
      AuthenticationSessionRemovalPersistence removal) {
    this.publication = publication;
    this.sessions = sessions;
    this.management = management;
    this.removal = removal;
  }

  /**
   * Listing requires full authority but does not refresh inactivity or rotate CSRF/cookie state.
   */
  public List<AuthenticationSessionManagementPersistence.SessionSummary> list(
      AuthenticationSessionTransport transport) {
    return publication.mutate(() -> management.list(sessions.lock(observe(transport))));
  }

  /**
   * Called only by the protected explicit user-interaction HTTP route, never by background
   * exchange.
   */
  public boolean activity(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          management.activity(sessions.lock(observe(transport)));
          return true;
        });
  }

  /**
   * Same-account recent authority may revoke itself or another target; cookie deletion stays
   * disabled.
   */
  public boolean revoke(AuthenticationSessionTransport transport, String target) {
    return publication.attempt(
        Event.SESSION_REVOKE,
        () ->
            publication.complete(
                Event.SESSION_REVOKE,
                () -> {
                  var parent = sessions.lock(observe(transport));
                  if (management.revoke(parent, target)) {
                    transport.invalidate();
                  }
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

  private static AuthenticationSessionTransport.Observation observe(
      AuthenticationSessionTransport transport) {
    return transport.observe().orElseThrow(AuthenticationFailedException::new);
  }
}
