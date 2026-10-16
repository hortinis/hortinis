package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import java.util.Map;

/** Minimized status and explicit CSRF bootstrap; reads do not renew normal-factor proof. */
public final class AuthenticationObservationService {
  private static final String STATE = "state";
  private static final String FULL = "full";
  private final AuthenticationPublication publication;
  private final AuthenticationSessionAdmissionPersistence admission;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationAccountPersistence accounts;

  /** Shares ordered current-session locks and the bounded admission transaction. */
  public AuthenticationObservationService(
      AuthenticationPublication publication,
      AuthenticationSessionAdmissionPersistence admission,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationAccountPersistence accounts) {
    this.publication = publication;
    this.admission = admission;
    this.sessions = sessions;
    this.accounts = accounts;
  }

  /** This explicit GET admits a bounded ceremony, reusing any native anonymous CSRF session. */
  public Map<String, String> csrf(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          var observed = transport.observe();
          if (observed.isEmpty()) {
            transport.bootstrap(admission::admitAnonymous);
          } else {
            try {
              sessions.lock(observed.orElseThrow());
            } catch (AuthenticationFailedException exception) {
              transport.invalidate();
              transport.bootstrap(admission::admitAnonymous);
            }
          }
          return Map.of(
              "token",
              transport.csrfValue().orElseThrow(AuthenticationFailedException::new),
              "headerName",
              "X-CSRF-TOKEN");
        });
  }

  /** No account, factor, setup-availability or scope metadata is disclosed by limited status. */
  public Map<String, String> status(AuthenticationSessionTransport transport) {
    var observed = transport.observe();
    if (observed.isEmpty()) {
      return Map.of(STATE, "anonymous");
    }
    try {
      return publication.mutate(
          () -> {
            var parent = sessions.lock(observed.orElseThrow());
            if (FULL.equals(parent.state())) {
              sessions.requireFull(parent);
              return Map.of(
                  "state",
                  "authenticated",
                  "accountId",
                  parent.account().toString(),
                  "logoutTarget",
                  parent.logoutTarget(),
                  "absoluteExpiresAt",
                  parent.expiresAt().toString());
            }
            return Map.of(STATE, parent.state());
          });
    } catch (AuthenticationFailedException exception) {
      return Map.of(STATE, "anonymous");
    }
  }

  /** Current server-owned account login for rate limits; never a request-selected account. */
  public String login(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          var parent =
              sessions.lock(transport.observe().orElseThrow(AuthenticationFailedException::new));
          if (parent.account() == null) {
            throw new AuthenticationFailedException();
          }
          return accounts.findAccount(parent.account()).login();
        });
  }
}
