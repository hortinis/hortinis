package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;

/** Minimal session observation and explicit native CSRF materialization. */
public final class AuthenticationObservationService {
  private final CurrentAuthenticationAuthorityPersistence authority;
  private final AuthenticationAttemptLimiter attempts;

  /** Public reads are bounded before allocating any anonymous session. */
  public AuthenticationObservationService(
      CurrentAuthenticationAuthorityPersistence authority, AuthenticationAttemptLimiter attempts) {
    this.authority = authority;
    this.attempts = attempts;
  }

  /** Materializes only Spring's deferred, per-request masked token. */
  public Map<String, String> csrf(HttpServletRequest request) {
    admit(request);
    var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  /** Does not allocate a session; stale/unverified/disabled contexts are anonymous. */
  public Map<String, String> status(HttpServletRequest request) {
    admit(request);
    var session = request.getSession(false);
    if (session != null) {
      try {
        var identity =
            AuthenticationIdentity.from(SecurityContextHolder.getContext().getAuthentication());
        if (authority.find(session.getId(), identity).isPresent()) {
          return authenticated(identity);
        }
      } catch (AuthenticationRequiredException exception) {
        return Map.of("state", "anonymous");
      }
    }
    return Map.of("state", "anonymous");
  }

  /** Closed success response for the native password filter; never publishes authentication. */
  public static Map<String, String> authenticated(AuthenticationIdentity identity) {
    return Map.of("state", "authenticated", "accountId", identity.account().toString());
  }

  private void admit(HttpServletRequest request) {
    if (!attempts.admitSource(request.getRemoteAddr())) {
      throw new AuthenticationOverloadedException();
    }
  }
}
