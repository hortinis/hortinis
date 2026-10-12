package com.hortinis.sync.authentication.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class HeaderOnlyCsrfTokenRequestHandlerTest {
  private static final String HEADER = "X-CSRF-TOKEN";
  private static final String PARAMETER = "_csrf";
  private static final String PATH = "/api/v1/auth/logout";
  private static final String ATTRIBUTE = "hortinis.csrf";

  @Test
  void establishedFilterAcceptsOnlyTheCurrentPlainHeaderWithoutChangingSessionOrCookie()
      throws Exception {
    String value = AuthenticationSecrets.randomValue();
    var request = request(value);
    request.addHeader(HEADER, value);
    var response = apply(request);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(request.getSession(false).getAttribute(ATTRIBUTE))
        .isInstanceOf(DefaultCsrfToken.class);
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"parameter", "missing", "wrong", "duplicate", "noncanonical"})
  void rejectsAllFallbackAndInvalidHeadersWithoutBodyParsing(String input) throws Exception {
    String value = AuthenticationSecrets.randomValue();
    var request = request(value);
    switch (input) {
      case "parameter" -> request.setParameter(PARAMETER, value);
      case "wrong" -> request.addHeader(HEADER, AuthenticationSecrets.randomValue());
      case "duplicate" -> {
        request.addHeader(HEADER, value);
        request.addHeader(HEADER, value);
      }
      case "noncanonical" -> request.addHeader(HEADER, value + "=");
      default -> {}
    }
    var response = apply(request);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void missingSessionDeniesWithoutAllocationOrTransactionalSave() throws Exception {
    var request = new ParameterForbiddenRequest("POST", PATH);
    request.addHeader(HEADER, AuthenticationSecrets.randomValue());
    assertThat(apply(request).getStatus()).isEqualTo(403);
    assertThat(request.getSession(false)).isNull();
    var safe = new ParameterForbiddenRequest("GET", "/api/v1/auth/status");
    assertThat(apply(safe).getStatus()).isEqualTo(200);
    assertThat(safe.getSession(false)).isNull();
  }

  private static MockHttpServletRequest request(String value) {
    var request = new ParameterForbiddenRequest("POST", PATH);
    var session = new MockHttpSession();
    session.setAttribute(ATTRIBUTE, new DefaultCsrfToken(HEADER, PARAMETER, value));
    request.setSession(session);
    return request;
  }

  private static MockHttpServletResponse apply(MockHttpServletRequest request) throws Exception {
    var filter = new CsrfFilter(new RandomSessionCsrfTokenRepository());
    filter.setRequestHandler(new HeaderOnlyCsrfTokenRequestHandler());
    filter.setAccessDeniedHandler(
        (req, response, failure) -> AuthenticationHttpErrors.DENIED.write(response));
    var response = new MockHttpServletResponse();
    var passed = new AtomicBoolean();
    filter.doFilter(request, response, (req, res) -> passed.set(true));
    assertThat(passed.get()).isEqualTo(response.getStatus() == 200);
    return response;
  }

  private static final class ParameterForbiddenRequest extends MockHttpServletRequest {
    ParameterForbiddenRequest(String method, String path) {
      super(method, path);
    }

    @Override
    public String getParameter(String name) {
      throw new AssertionError("CSRF request handling must never read form/query parameters.");
    }
  }
}
