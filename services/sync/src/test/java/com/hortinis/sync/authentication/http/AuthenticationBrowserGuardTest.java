package com.hortinis.sync.authentication.http;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationBrowserGuardTest {
  private static final String POST = "POST";
  private static final String GET = "GET";
  private static final String ORIGIN = "https://localhost:9443";
  private static final String LOGIN = "/api/v1/auth/login/password";
  private static final String CSRF = "/api/v1/auth/csrf";
  private static final String SITE = "Sec-Fetch-Site";
  private static final String MODE = "Sec-Fetch-Mode";
  private static final String DEST = "Sec-Fetch-Dest";
  private static final String ORIGIN_HEADER = "Origin";
  private static final String JSON = "application/json";

  @Test
  void permitsExactUnsafeAndSameOriginFetchGetWithoutReadingCredentialsOrForwarding()
      throws Exception {
    var unsafe = request(POST, LOGIN);
    unsafe.addHeader("Host", "untrusted.invalid");
    unsafe.addHeader("Forwarded", "for=synthetic;proto=http;host=untrusted.invalid");
    assertThat(apply(unsafe).getStatus()).isEqualTo(200);
    var safe = request(GET, CSRF);
    safe.removeHeader(ORIGIN_HEADER);
    assertThat(apply(safe).getStatus()).isEqualTo(200);
  }

  @ParameterizedTest
  @MethodSource("invalidHeaders")
  void deniesMissingOpaqueCrossSiteRepeatedAndNavigationHeadersBeforeReadingTheBody(
      String header, String value, boolean duplicate) throws Exception {
    var request = request(POST, LOGIN);
    if (!duplicate) {
      request.removeHeader(header);
    }
    if (value != null) {
      request.addHeader(header, value);
    }
    var response = apply(request);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  static Stream<Arguments> invalidHeaders() {
    return Stream.of(
        Arguments.of(ORIGIN_HEADER, null, false),
        Arguments.of(ORIGIN_HEADER, "null", false),
        Arguments.of(ORIGIN_HEADER, "https://untrusted.invalid", false),
        Arguments.of(ORIGIN_HEADER, ORIGIN + "/", false),
        Arguments.of(ORIGIN_HEADER, ORIGIN, true),
        Arguments.of(SITE, null, false),
        Arguments.of(SITE, "cross-site", false),
        Arguments.of(SITE, "same-site", false),
        Arguments.of(SITE, "none", false),
        Arguments.of(SITE, "same-origin", true),
        Arguments.of(MODE, null, false),
        Arguments.of(MODE, "navigate", false),
        Arguments.of(MODE, "cors", true),
        Arguments.of(DEST, null, false),
        Arguments.of(DEST, "document", false),
        Arguments.of(DEST, "empty", true),
        Arguments.of("Sec-Fetch-User", "?1", false));
  }

  @ParameterizedTest
  @ValueSource(strings = {GET, "HEAD"})
  void csrfBootstrapAndStatusCannotBeLoadedAsNavigationOrCrossSite(String method) throws Exception {
    var request = request(method, CSRF);
    request.removeHeader(SITE);
    assertThat(apply(request).getStatus()).isEqualTo(403);
    request.addHeader(SITE, "same-origin");
    request.addHeader(ORIGIN_HEADER, ORIGIN);
    assertThat(apply(request).getStatus()).isEqualTo(403);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text/plain",
        "application/x-www-form-urlencoded",
        "multipart/form-data",
        "application/json; charset=iso-8859-1",
        "application/problem+json",
        "invalid"
      })
  void rejectsUnsafeSimpleOrUnselectedMediaTypes(String type) throws Exception {
    var request = request(POST, LOGIN);
    request.removeHeader(HttpHeaders.CONTENT_TYPE);
    request.addHeader(HttpHeaders.CONTENT_TYPE, type);
    assertThat(apply(request).getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsRepeatedContentTypeAndAuthenticationQueries() throws Exception {
    var request = request(POST, LOGIN);
    ((UnreadableRequest) request).repeatContentType = true;
    assertThat(apply(request).getStatus()).isEqualTo(400);
    request = request(GET, CSRF);
    request.setQueryString("synthetic-private-canary");
    var response = apply(request);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).doesNotContain("synthetic-private-canary");
  }

  @Test
  void publicSafeRequestsReceiveFirstPartyHeadersAndHstsRequiresExplicitValidation()
      throws Exception {
    var request = new MockHttpServletRequest(GET, "/actuator/health");
    var response = apply(request);
    assertThat(response.getHeader("Content-Security-Policy"))
        .contains(
            "script-src 'self'", "connect-src 'self'", "object-src 'none'", "worker-src 'self'")
        .doesNotContain("unsafe-eval", "script-src 'unsafe-inline'");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(response.getHeader("Permissions-Policy"))
        .contains("geolocation=()", "publickey-credentials-get=(self)");
    assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    response = new MockHttpServletResponse();
    new AuthenticationBrowserGuard(ORIGIN, true).doFilter(request, response, (req, res) -> {});
    assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new UnreadableRequest(method, path);
    request.addHeader(ORIGIN_HEADER, ORIGIN);
    request.addHeader(SITE, "same-origin");
    request.addHeader(MODE, "cors");
    request.addHeader(DEST, "empty");
    request.addHeader(HttpHeaders.CONTENT_TYPE, JSON);
    return request;
  }

  private static MockHttpServletResponse apply(MockHttpServletRequest request) throws Exception {
    var response = new MockHttpServletResponse();
    var passed = new AtomicBoolean();
    new AuthenticationBrowserGuard(ORIGIN, false)
        .doFilter(request, response, (req, res) -> passed.set(true));
    assertThat(passed.get()).isEqualTo(response.getStatus() == 200);
    return response;
  }

  private static final class UnreadableRequest extends MockHttpServletRequest {
    private boolean repeatContentType;

    UnreadableRequest(String method, String path) {
      super(method, path);
    }

    @Override
    public java.util.Enumeration<String> getHeaders(String name) {
      return repeatContentType && HttpHeaders.CONTENT_TYPE.equals(name)
          ? java.util.Collections.enumeration(java.util.List.of(JSON, JSON))
          : super.getHeaders(name);
    }

    @Override
    public ServletInputStream getInputStream() {
      throw new AssertionError("Request guards must not read credential bodies.");
    }
  }
}
