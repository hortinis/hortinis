package com.hortinis.sync.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SynchronizationOriginFilterTest {
  private static final String LOCAL_ORIGIN = "http://localhost";
  private static final String ORIGIN = "Origin";
  private static final String FETCH_SITE = "Sec-Fetch-Site";
  private final SynchronizationOriginFilter filter =
      new SynchronizationOriginFilter(
          new MockEnvironment().withProperty("hortinis.access.public-origin", LOCAL_ORIGIN));

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "null",
        "https://private-origin-canary.example/path",
        "ftp://localhost",
        "http://localhost:0",
        "http://localhost:65536",
        "http://localhost:",
        "http://user@localhost",
        "http://localhost?private",
        "http://localhost#private"
      })
  void invalidOrMissingPublicOriginFailsWithoutEchoingItsValue(String value) {
    var environment = new MockEnvironment();
    if (value != null) {
      environment.withProperty("hortinis.access.public-origin", value);
    }
    assertThatThrownBy(() -> new SynchronizationOriginFilter(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("An explicit valid public browser origin is required.");
  }

  @Test
  void forgedHostCannotAuthorizeForeignOrigin() throws Exception {
    var request = request();
    request.setServerName("malicious.example");
    request.addHeader(ORIGIN, "http://malicious.example");
    assertDenied(request);
  }

  @Test
  void configuredPublicOriginWorksBehindAnEdgeWithoutForwardedHeaderTrust() throws Exception {
    var edgeFilter =
        new SynchronizationOriginFilter(
            new MockEnvironment()
                .withProperty("hortinis.access.public-origin", "https://public.example"));
    var request = request();
    request.addHeader(ORIGIN, "https://public.example");
    AtomicBoolean reached = new AtomicBoolean();
    edgeFilter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
    assertThat(reached).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://malicious.example",
        "null",
        "http://localhost:81",
        "http://localhost/",
        "http://user@localhost",
        "http://localhost?scope=private",
        "http://localhost#private",
        "malformed origin"
      })
  void rejectsForeignOpaqueAndMalformedOriginsBeforeWorkflow(String origin) throws Exception {
    var request = request();
    request.addHeader(ORIGIN, origin);
    request.addHeader("Forwarded", "host=malicious.example;proto=https");
    request.addHeader("X-Forwarded-Host", "malicious.example");
    assertDenied(request);
  }

  @Test
  void rejectsCrossSiteSameSiteAndDuplicateHeaders() throws Exception {
    for (String site : new String[] {"cross-site", "same-site", "invalid"}) {
      var request = request();
      request.addHeader(FETCH_SITE, site);
      assertDenied(request);
    }
    var duplicateOrigin = request();
    duplicateOrigin.addHeader(ORIGIN, LOCAL_ORIGIN);
    duplicateOrigin.addHeader(ORIGIN, LOCAL_ORIGIN);
    assertDenied(duplicateOrigin);
    var duplicateSite = request();
    duplicateSite.addHeader(FETCH_SITE, "same-origin");
    duplicateSite.addHeader(FETCH_SITE, "cross-site");
    assertDenied(duplicateSite);
  }

  @Test
  void acceptsSameOriginAndNonBrowserRequestsWithoutGrantingScopeAuthority() throws Exception {
    for (String origin : new String[] {null, LOCAL_ORIGIN, "http://localhost:80"}) {
      var request = request();
      if (origin != null) {
        request.addHeader(ORIGIN, origin);
        request.addHeader(FETCH_SITE, "same-origin");
      }
      AtomicBoolean reached = new AtomicBoolean();
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
      assertThat(reached).isTrue();
    }
  }

  @Test
  void mutationGuardDoesNotDependOnRawPathMatching() throws Exception {
    for (String path :
        new String[] {
          "/api;parameter/v1/sync/operations",
          "/api//v1/sync/operations",
          "/api%2fv1/sync/operations"
        }) {
      var request = new MockHttpServletRequest("POST", path);
      request.addHeader(ORIGIN, "https://malicious.example");
      assertDenied(request);
    }
  }

  @Test
  void readOnlyHealthAndChangesDoNotUseTheMutationGuard() throws Exception {
    for (String path : new String[] {"/actuator/health", "/api/v1/sync/changes"}) {
      var request = new MockHttpServletRequest("GET", path);
      request.addHeader(ORIGIN, "https://malicious.example");
      AtomicBoolean reached = new AtomicBoolean();
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
      assertThat(reached).isTrue();
    }
  }

  private void assertDenied(MockHttpServletRequest request) throws Exception {
    var response = new MockHttpServletResponse();
    AtomicBoolean reached = new AtomicBoolean();
    filter.doFilter(request, response, (req, res) -> reached.set(true));
    assertThat(reached).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access is denied.\"}");
  }

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("POST", "/api/v1/sync/operations");
  }
}
