package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ControlledSessionIdResolverTest {
  private static final String GET_METHOD = "GET";
  private static final String COOKIE_NAME = "__Host-hortinis-session";
  private static final String CSRF_PATH = "/api/v1/auth/csrf";
  private final ControlledSessionIdResolver resolver = new ControlledSessionIdResolver();

  @Test
  void controlledWriteAlwaysUsesStrictSecureHostSessionCookieEvenWithoutTlsForwarding() {
    MockHttpServletRequest request = new MockHttpServletRequest(GET_METHOD, CSRF_PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    String identifier = AuthenticationSecrets.randomValue();
    ControlledSessionIdResolver.authorizeCookieWrite(request, identifier);
    resolver.setSessionId(request, response, identifier);
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
        .contains(COOKIE_NAME + "=", "Path=/", "Secure", "HttpOnly", "SameSite=Strict")
        .doesNotContain("Domain=", "Max-Age=", "Expires=");
  }

  @Test
  void requiresExplicitAdapterAuthorizationAndDeniesLogoutOrDataCookieMutation() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    resolver.setSessionId(
        new MockHttpServletRequest(GET_METHOD, CSRF_PATH),
        response,
        AuthenticationSecrets.randomValue());
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    for (String path :
        new String[] {
          "/api/v1/auth/logout",
          "/api/v1/sync/operations",
          "/api/v1/auth/activity",
          "/api/v1/auth/session"
        }) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      String identifier = AuthenticationSecrets.randomValue();
      ControlledSessionIdResolver.authorizeCookieWrite(request, identifier);
      resolver.setSessionId(request, response, identifier);
      resolver.expireSession(request, response);
      assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
  }

  @Test
  void authorizationCannotReleaseDifferentOrLaterUncommittedIdentifier() {
    MockHttpServletRequest request = new MockHttpServletRequest(GET_METHOD, CSRF_PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    String committed = AuthenticationSecrets.randomValue();
    ControlledSessionIdResolver.authorizeCookieWrite(request, committed);
    resolver.setSessionId(request, response, AuthenticationSecrets.randomValue());
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    resolver.setSessionId(request, response, committed);
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
  }

  @Test
  void resolvesOneCanonicalSecretAndRejectsDuplicateAliasOrIdentifierCookies() {
    MockHttpServletRequest request = new MockHttpServletRequest(GET_METHOD, "/api/v1/auth/session");
    String secret = AuthenticationSecrets.randomValue();
    request.setCookies(new Cookie(COOKIE_NAME, secret));
    assertThat(resolver.resolveSessionIds(request)).containsExactly(secret);
    request.setCookies(new Cookie(COOKIE_NAME, secret), new Cookie(COOKIE_NAME, secret));
    assertThat(resolver.resolveSessionIds(request)).isEmpty();
    for (String invalid :
        new String[] {java.util.UUID.randomUUID().toString(), secret + "=", "A".repeat(42) + "B"}) {
      request.setCookies(new Cookie(COOKIE_NAME, invalid));
      assertThat(resolver.resolveSessionIds(request)).isEmpty();
    }
    request.setCookies(new Cookie("other-cookie", secret));
    assertThat(resolver.resolveSessionIds(request)).isEmpty();
  }
}
