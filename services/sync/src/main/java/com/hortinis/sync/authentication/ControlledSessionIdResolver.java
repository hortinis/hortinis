package com.hortinis.sync.authentication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

/** Strict host cookie with explicit workflow-only writes and no late logout/expiry deletion. */
public final class ControlledSessionIdResolver implements HttpSessionIdResolver {
  private static final String WRITE_AUTHORIZED =
      ControlledSessionIdResolver.class.getName() + ".write";
  private static final Set<String> COOKIE_POST_PATHS =
      Set.of(
          "/api/v1/auth/setup",
          "/api/v1/auth/setup/passkey",
          "/api/v1/auth/login/password",
          "/api/v1/auth/login/passkey",
          "/api/v1/auth/step-up/password",
          "/api/v1/auth/step-up/passkey",
          "/api/v1/auth/password",
          "/api/v1/auth/credentials/options",
          "/api/v1/auth/credentials",
          "/api/v1/auth/credentials/revoke",
          "/api/v1/auth/recovery-codes",
          "/api/v1/auth/recovery/password/options",
          "/api/v1/auth/recovery/password/proof",
          "/api/v1/auth/recovery/password",
          "/api/v1/auth/recovery/passkey",
          "/api/v1/auth/recovery/passkey/options",
          "/api/v1/auth/recovery/passkey/proof",
          "/api/v1/auth/recovery/operator",
          "/api/v1/auth/recovery/operator/proof");
  private final CookieHttpSessionIdResolver delegate = new CookieHttpSessionIdResolver();

  /**
   * Forces the selected production cookie attributes regardless of request forwarding/TLS headers.
   */
  public ControlledSessionIdResolver() {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName("__Host-hortinis-session");
    serializer.setCookiePath("/");
    serializer.setUseSecureCookie(true);
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Strict");
    serializer.setCookieMaxAge(-1);
    serializer.setUseBase64Encoding(false);
    delegate.setCookieSerializer(serializer);
  }

  /** Called only by the trusted controlled-workflow adapter after authority/admission succeeds. */
  public static void authorizeCookieWrite(HttpServletRequest request, String committedIdentifier) {
    if (!AuthenticationSecrets.isRandomValue(committedIdentifier)) {
      throw new IllegalArgumentException("Invalid committed session identifier.");
    }
    request.setAttribute(WRITE_AUTHORIZED, committedIdentifier);
  }

  @Override
  public List<String> resolveSessionIds(HttpServletRequest request) {
    List<String> ids = delegate.resolveSessionIds(request);
    return ids.size() == 1 && AuthenticationSecrets.isRandomValue(ids.getFirst()) ? ids : List.of();
  }

  @Override
  public void setSessionId(
      HttpServletRequest request, HttpServletResponse response, String sessionId) {
    Object authorization = request.getAttribute(WRITE_AUTHORIZED);
    if (isCookieWorkflow(request) && authorization instanceof String committedIdentifier) {
      if (!AuthenticationSecrets.isRandomValue(sessionId)) {
        throw new IllegalStateException("Invalid authentication session identifier.");
      }
      if (committedIdentifier.equals(sessionId)) {
        delegate.setSessionId(request, response, sessionId);
      }
    }
  }

  /** Closed cookie-changing workflows shared with the transactional servlet transport adapter. */
  public static boolean isCookieWorkflow(HttpServletRequest request) {
    return ("GET".equals(request.getMethod())
            && "/api/v1/auth/csrf".equals(request.getRequestURI()))
        || ("POST".equals(request.getMethod())
            && COOKIE_POST_PATHS.contains(request.getRequestURI()));
  }

  @Override
  public void expireSession(HttpServletRequest request, HttpServletResponse response) {
    // Server authority is invalidated synchronously. A deletion response could clear a newer
    // cookie;
    // leave the invalid browser cookie until a coordinated login replaces it or the browser closes.
  }
}
