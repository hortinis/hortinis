package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.ControlledSessionIdResolver;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.http.RequestAuthenticationSession;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual framework servlet wrappers and JDBC transport; no factor/HTTP runtime activation proof.
 */
@Testcontainers
class AuthenticationServletSessionIntegrationTest {
  private static final String GET_METHOD = "GET";
  private static final String POST_METHOD = "POST";
  private static final String COOKIE_NAME = "__Host-hortinis-session";
  private static final String CSRF_PATH = "/api/v1/auth/csrf";
  private static final String LOGIN_PATH = "/api/v1/auth/login/password";
  private static final String AUTHORITY_COUNT =
      "SELECT count(*) FROM authentication_session_authority";
  private static final String TRANSPORT_COUNT = "SELECT count(*) FROM spring_session";
  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactionManager;

  @BeforeEach
  void freshSchema() {
    String schema = "authentication_servlet_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    transactionManager = new DataSourceTransactionManager(source);
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
  }

  @Test
  void nativeBootstrapReleasesExactCookieOnlyAfterCommitAndStoresClosedCsrf() throws Exception {
    MockHttpServletResponse response =
        request(
            GET_METHOD,
            CSRF_PATH,
            null,
            (nativeRequest, nativeResponse) -> {
              var transport = new RequestAuthenticationSession(nativeRequest, nativeResponse);
              assertThat(transport.observe()).isEmpty();
              assertThat(transport.csrfValue()).isEmpty();
              transaction()
                  .executeWithoutResult(
                      status -> {
                        bootstrap(transport);
                        assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
                        MockHttpServletResponse premature = new MockHttpServletResponse();
                        new ControlledSessionIdResolver()
                            .setSessionId(
                                nativeRequest, premature, nativeRequest.getSession(false).getId());
                        assertThat(premature.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
                      });
              assertThat(AuthenticationSecrets.isRandomValue(transport.csrfValue().orElseThrow()))
                  .isTrue();
            });
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
        .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/")
        .doesNotContain("Domain=", "Max-Age=", "Expires=");
    Cookie cookie = cookie(response);
    assertThat(
            jdbc.queryForObject(
                "SELECT session_verifier FROM authentication_session_authority", String.class))
        .isEqualTo(AuthenticationSecrets.digest(cookie.getValue()));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void rolledBackBootstrapDoesNotEmitCookieOrResurrectRowsOnFinalServletSave() throws Exception {
    MockHttpServletResponse response =
        request(
            GET_METHOD,
            CSRF_PATH,
            null,
            (nativeRequest, nativeResponse) ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          bootstrap(
                              new RequestAuthenticationSession(nativeRequest, nativeResponse));
                          status.setRollbackOnly();
                        }));
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(TRANSPORT_COUNT)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isZero();
  }

  @Test
  void rotationCommitsNativeCookieReferenceAndCsrfBeforeResponseSave() throws Exception {
    verifyRotation(false);
  }

  @Test
  void rollbackPreservesPredecessorAndCannotEmitChangedInMemoryCookie() throws Exception {
    verifyRotation(true);
  }

  private void verifyRotation(boolean rollback) throws Exception {
    Cookie previous = cookie(bootstrapResponse());
    UUID oldReference =
        jdbc.queryForObject(
            "SELECT session_reference FROM authentication_session_authority", UUID.class);
    UUID replacement = UUID.randomUUID();
    String oldCsrf = csrfFrom(previous);
    MockHttpServletResponse response =
        request(
            POST_METHOD,
            LOGIN_PATH,
            previous,
            (nativeRequest, nativeResponse) -> {
              var transport = new RequestAuthenticationSession(nativeRequest, nativeResponse);
              transaction()
                  .executeWithoutResult(
                      status -> {
                        var changed =
                            transport.rotate(
                                new SessionAuthorityReference(replacement),
                                AuthenticationLifetime.LIMITED_ABSOLUTE);
                        jdbc.update(
                            "UPDATE authentication_session_authority SET session_reference = ?, "
                                + "session_verifier = ? WHERE session_reference = ?",
                            replacement,
                            changed.verifier(),
                            oldReference);
                        assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
                        assertThat(transport.csrfValue().orElseThrow()).isNotEqualTo(oldCsrf);
                        if (rollback) {
                          status.setRollbackOnly();
                        }
                      });
            });
    if (rollback) {
      assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
      assertThat(csrfFrom(previous)).isEqualTo(oldCsrf);
      assertThat(jdbc.queryForObject("SELECT session_id FROM spring_session", String.class))
          .isEqualTo(previous.getValue());
    } else {
      Cookie current = cookie(response);
      assertThat(current.getValue().equals(previous.getValue())).isFalse();
      assertThat(csrfFrom(current)).isNotEqualTo(oldCsrf);
      request(
          GET_METHOD,
          CSRF_PATH,
          previous,
          (nativeRequest, nativeResponse) ->
              assertThat(new RequestAuthenticationSession(nativeRequest, nativeResponse).observe())
                  .isEmpty());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT session_reference FROM authentication_session_authority", UUID.class))
        .isEqualTo(rollback ? oldReference : replacement);
  }

  @Test
  void earlierCommitCannotAuthorizeLaterRolledBackIdentifierInSameRequest() throws Exception {
    MockHttpServletResponse response =
        request(
            GET_METHOD,
            CSRF_PATH,
            null,
            (nativeRequest, nativeResponse) -> {
              var transport = new RequestAuthenticationSession(nativeRequest, nativeResponse);
              transaction().executeWithoutResult(status -> bootstrap(transport));
              transaction()
                  .executeWithoutResult(
                      status -> {
                        transport.rotate(
                            new SessionAuthorityReference(UUID.randomUUID()),
                            AuthenticationLifetime.LIMITED_ABSOLUTE);
                        status.setRollbackOnly();
                      });
            });
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
  }

  @Test
  void logoutInvalidationIsImmediateAtomicAndNeverDeletesBrowserCookie() throws Exception {
    Cookie previous = cookie(bootstrapResponse());
    MockHttpServletResponse response =
        request(
            POST_METHOD,
            "/api/v1/auth/logout",
            previous,
            (nativeRequest, nativeResponse) ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          new RequestAuthenticationSession(nativeRequest, nativeResponse)
                              .invalidate();
                          jdbc.update("DELETE FROM authentication_session_authority");
                          assertThat(count(TRANSPORT_COUNT)).isZero();
                        }));
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(AUTHORITY_COUNT)).isZero();
  }

  @Test
  void explicitCsrfRefreshDoesNotRotateCookieOrRenewAuthorityDeadline() throws Exception {
    Cookie previous = cookie(bootstrapResponse());
    String oldCsrf = csrfFrom(previous);
    var deadline =
        jdbc.queryForObject(
            "SELECT expires_at FROM authentication_session_authority", java.sql.Timestamp.class);
    MockHttpServletResponse response =
        request(
            GET_METHOD,
            CSRF_PATH,
            previous,
            (nativeRequest, nativeResponse) -> {
              var transport = new RequestAuthenticationSession(nativeRequest, nativeResponse);
              transaction().executeWithoutResult(status -> transport.refreshCsrf());
            });
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(csrfFrom(previous)).isNotEqualTo(oldCsrf);
    assertThat(
            jdbc.queryForObject(
                "SELECT expires_at FROM authentication_session_authority",
                java.sql.Timestamp.class))
        .isEqualTo(deadline);
  }

  @Test
  void unsafeDataCannotCreateRotateOrRefreshSessionTransport() throws Exception {
    MockHttpServletResponse response =
        request(
            POST_METHOD,
            "/api/v1/sync/operations",
            null,
            (nativeRequest, nativeResponse) ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          var transport =
                              new RequestAuthenticationSession(nativeRequest, nativeResponse);
                          assertThatThrownBy(() -> bootstrap(transport))
                              .isInstanceOf(IllegalStateException.class);
                          assertThatThrownBy(
                                  () ->
                                      transport.rotate(
                                          new SessionAuthorityReference(UUID.randomUUID()),
                                          AuthenticationLifetime.FULL_ABSOLUTE))
                              .isInstanceOf(IllegalStateException.class);
                          assertThatThrownBy(transport::refreshCsrf)
                              .isInstanceOf(IllegalStateException.class);
                        }));
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void deferredCsrfCannotAllocateTransportOutsideBoundedPublication() throws Exception {
    MockHttpServletResponse response =
        request(
            POST_METHOD,
            LOGIN_PATH,
            null,
            (nativeRequest, nativeResponse) -> {
              var csrf =
                  new com.hortinis.sync.authentication.http.RandomSessionCsrfTokenRepository();
              var deferred = csrf.loadDeferredToken(nativeRequest, nativeResponse);
              assertThat(deferred.isGenerated()).isTrue();
              assertThat(deferred.get()).isSameAs(deferred.get());
              assertThat(AuthenticationSecrets.isRandomValue(deferred.get().getToken())).isTrue();
              assertThat(csrf.loadToken(nativeRequest)).isNull();
              var guard = new org.springframework.security.web.csrf.CsrfFilter(csrf);
              guard.setRequestHandler(
                  new com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler());
              guard.setAccessDeniedHandler(
                  (request, denied, failure) ->
                      com.hortinis.sync.authentication.http.AuthenticationHttpErrors.DENIED.write(
                          denied));
              try {
                guard.doFilter(
                    nativeRequest,
                    nativeResponse,
                    (request, ignored) -> {
                      throw new AssertionError("Missing CSRF must deny before workflow execution.");
                    });
              } catch (java.io.IOException | jakarta.servlet.ServletException exception) {
                throw new AssertionError("Unexpected CSRF filter failure.", exception);
              }
              assertThat(nativeRequest.getSession(false)).isNull();
            });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(TRANSPORT_COUNT)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isZero();
  }

  @Test
  void transportWritesRequireWritableTransactionAndFreshRotationReference() throws Exception {
    Cookie previous = cookie(bootstrapResponse());
    request(
        POST_METHOD,
        LOGIN_PATH,
        previous,
        (nativeRequest, nativeResponse) -> {
          var transport = new RequestAuthenticationSession(nativeRequest, nativeResponse);
          assertThatThrownBy(
                  () ->
                      transport.rotate(
                          new SessionAuthorityReference(UUID.randomUUID()),
                          AuthenticationLifetime.FULL_ABSOLUTE))
              .isInstanceOf(IllegalStateException.class);
          TransactionTemplate readOnly = transaction();
          readOnly.setReadOnly(true);
          assertThatThrownBy(
                  () ->
                      readOnly.executeWithoutResult(
                          status ->
                              transport.rotate(
                                  new SessionAuthorityReference(UUID.randomUUID()),
                                  AuthenticationLifetime.FULL_ABSOLUTE)))
              .isInstanceOf(IllegalStateException.class);
          transaction()
              .executeWithoutResult(
                  status -> {
                    var current = transport.observe().orElseThrow();
                    assertThatThrownBy(
                            () ->
                                transport.rotate(
                                    current.reference(), AuthenticationLifetime.FULL_ABSOLUTE))
                        .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                            () ->
                                transport.rotate(
                                    new SessionAuthorityReference(UUID.randomUUID()),
                                    java.time.Duration.ofDays(1)))
                        .isInstanceOf(IllegalArgumentException.class);
                  });
        });
  }

  private MockHttpServletResponse bootstrapResponse() throws Exception {
    return request(
        GET_METHOD,
        CSRF_PATH,
        null,
        (nativeRequest, nativeResponse) ->
            transaction()
                .executeWithoutResult(
                    status ->
                        bootstrap(
                            new RequestAuthenticationSession(nativeRequest, nativeResponse))));
  }

  private void bootstrap(AuthenticationSessionTransport transport) {
    transport.bootstrap(
        new AuthenticationSessionAdmissionPersistence(
                JdbcClient.create(jdbc), Clock.fixed(NOW, ZoneOffset.UTC))
            ::admitAnonymous);
  }

  private String csrfFrom(Cookie cookie) throws Exception {
    var value = new java.util.concurrent.atomic.AtomicReference<String>();
    MockHttpServletResponse response =
        request(
            GET_METHOD,
            "/api/v1/auth/session",
            cookie,
            (nativeRequest, nativeResponse) ->
                value.set(
                    new RequestAuthenticationSession(nativeRequest, nativeResponse)
                        .csrfValue()
                        .orElseThrow()));
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    return value.get();
  }

  private MockHttpServletResponse request(
      String method,
      String path,
      Cookie cookie,
      BiConsumer<HttpServletRequest, HttpServletResponse> operation)
      throws Exception {
    return request(
        AuthenticationJdbcSessions.create(jdbc, transactionManager),
        method,
        path,
        cookie,
        operation);
  }

  private <S extends Session> MockHttpServletResponse request(
      SessionRepository<S> repository,
      String method,
      String path,
      Cookie cookie,
      BiConsumer<HttpServletRequest, HttpServletResponse> operation)
      throws Exception {
    var filter = new SessionRepositoryFilter<>(repository);
    filter.setHttpSessionIdResolver(new ControlledSessionIdResolver());
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (cookie != null) {
      request.setCookies(cookie);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (wrappedRequest, wrappedResponse) ->
            operation.accept(
                (HttpServletRequest) wrappedRequest, (HttpServletResponse) wrappedResponse));
    return response;
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private int count(String trustedQuery) {
    return java.util.Objects.requireNonNull(jdbc.queryForObject(trustedQuery, Integer.class));
  }

  private static Cookie cookie(MockHttpServletResponse response) {
    String header = java.util.Objects.requireNonNull(response.getHeader(HttpHeaders.SET_COOKIE));
    String value = header.substring(COOKIE_NAME.length() + 1, header.indexOf(';'));
    assertThat(AuthenticationSecrets.isRandomValue(value)).isTrue();
    return new Cookie(COOKIE_NAME, value);
  }
}
