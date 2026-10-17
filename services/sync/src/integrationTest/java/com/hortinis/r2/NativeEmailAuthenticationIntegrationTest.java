package com.hortinis.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.HortinisSyncApplication;
import com.hortinis.sync.access.AccountId;
import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.access.SyntheticAccessContexts;
import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.CryptoWorkLimiter;
import com.hortinis.sync.authentication.PasswordVerifier;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionRemovalPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.configuration.SynchronizationAccessConfiguration;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import com.hortinis.sync.persistence.SynchronizationScopePersistence;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * New R2 evidence: real provider/filter/JDBC sessions; no manually authenticated login fixtures.
 */
@Testcontainers
@SpringBootTest(
    classes = NativeEmailAuthenticationIntegrationTest.NativeFixtureConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class NativeEmailAuthenticationIntegrationTest {
  private static final String JSON_MEDIA = "application/json";
  private static final String KEYSTORE_FILE = "runtime.p12";
  private static final String STATUS_PATH = "/api/v1/auth/session";
  private static final String BOOTSTRAP_PATH = "/api/v1/sync/bootstrap";
  private static final String LOGOUT_PATH = "/api/v1/auth/logout";
  private static final String DENIED_CODE = "ACCESS_DENIED";
  private static final String PENDING = "pending";
  private static final String DISABLED = "disabled";
  private static final String UNVERIFIED = "unverified";
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String INVALID_CODE = "INVALID_REQUEST";
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String ORIGIN_HEADER = "Origin";
  private static final String REQUIRED_CODE = "AUTHENTICATION_REQUIRED";
  private static final String ORIGIN = "https://localhost";
  private static final String COOKIE_NAME = "__Host-hortinis-session";
  private static final String PASSWORD = " synthetic+phrase&=🌱 ";
  private static final String EMAIL = "Gardener+tag@Example.test";
  private static final String FORM_MEDIA = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
  private static final String CONTEXT =
      HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
  private static final String TLS_PASSWORD = "r2-synthetic-tls-password";
  private static final Path TLS_DIRECTORY = createTlsMaterial();
  private static final String VERIFIER =
      new PasswordVerifier(new CryptoWorkLimiter()).encode(PASSWORD);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private MockMvc http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcIndexedSessionRepository sessions;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private AuthenticationSessionRemovalPersistence removal;
  @MockitoSpyBean private CurrentAuthenticationAuthorityPersistence authority;

  @MockitoSpyBean
  private com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence accounts;

  @Autowired private SynchronizationScopePersistence scopes;
  @MockitoBean private AuthenticationAttemptLimiter attempts;
  @MockitoSpyBean private PasswordVerifier encoder;
  @LocalServerPort private int port;
  private UUID account;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("hortinis.access.mode", () -> "single-user-auth");
    registry.add("hortinis.access.public-origin", () -> ORIGIN);
    registry.add("hortinis.access.tls-validated", () -> "true");
    registry.add("server.ssl.enabled", () -> "true");
    registry.add("server.ssl.key-store", () -> TLS_DIRECTORY.resolve(KEYSTORE_FILE).toString());
    registry.add("server.ssl.key-store-password", () -> TLS_PASSWORD);
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-alias", () -> "hortinis-test");
  }

  @BeforeEach
  void soleVerifiedSyntheticAccount() {
    jdbc.update("DELETE FROM spring_session");
    jdbc.update("DELETE FROM technical_exchange_expectation");
    jdbc.update("DELETE FROM authentication_account");
    jdbc.update("DELETE FROM authentication_security_event");
    account =
        jdbc.queryForObject(
            "INSERT INTO authentication_account (server_instance_id, synchronization_scope_id, "
                + "delivery_email, canonical_email, status, password_verifier, "
                + "created_at, verified_at) "
                + "SELECT server_instance_id, synchronization_scope_id, ?, ?, "
                + "'active', ?, now(), now() "
                + "FROM technical_synchronization_scope WHERE technical_default"
                + " RETURNING account_id ",
            UUID.class,
            EMAIL,
            EMAIL.toLowerCase(java.util.Locale.ROOT),
            VERIFIER);
    when(attempts.admit(anyString(), anyString())).thenReturn(true);
    when(attempts.admitSource(anyString())).thenReturn(true);
    clearInvocations(encoder);
  }

  @Test
  void loginUsesNativeProviderFixationContextIndexAndMinimalStatus(CapturedOutput output)
      throws Exception {
    Browser anonymous = bootstrap();
    Browser logged = login(anonymous);
    assertThat(logged.cookie().getValue()).isNotEqualTo(anonymous.cookie().getValue());
    assertThat(sessions.findById(anonymous.cookie().getValue())).isNull();
    org.springframework.session.Session stored = sessions.findById(logged.cookie().getValue());
    SecurityContext context = stored.getAttribute(CONTEXT);
    assertThat(AuthenticationIdentity.from(context.getAuthentication()))
        .isEqualTo(new AuthenticationIdentity(account, 1));
    assertThat(sessions.findByPrincipalName(account.toString())).hasSize(1);
    assertThat(stored.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofMinutes(30));
    assertThat(stored.getAttributeNames())
        .doesNotContain(
            "hortinis.authority",
            "hortinis.ceremony",
            "hortinis.logout-target",
            "SPRING_SECURITY_SAVED_REQUEST");
    assertThat(
            jdbc.queryForList(
                    "SELECT convert_from(attribute_bytes, 'UTF8') FROM "
                        + "spring_session_attributes",
                    String.class)
                .toString())
        .doesNotContain(EMAIL, PASSWORD, VERIFIER, "WebAuthenticationDetails");
    assertThat(body(read(STATUS_PATH, logged)))
        .isEqualTo(
            JSON.readTree("{\"state\":\"authenticated\",\"accountId\":\"" + account + "\"}"));
    assertThat(read(BOOTSTRAP_PATH, logged).getStatus()).isEqualTo(200);
    var staleCsrf = mutate(LOGOUT_PATH, logged.cookie(), anonymous.token(), null, null);
    assertError(staleCsrf, 403, DENIED_CODE);
    verify(encoder, atLeastOnce()).matches(PASSWORD, VERIFIER);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_security_event "
                    + "WHERE event = 'login' AND outcome = 'succeeded' AND reason = 'completed'",
                Integer.class))
        .isEqualTo(1);
    assertThat(output.getAll())
        .doesNotContain(
            EMAIL,
            PASSWORD,
            VERIFIER,
            logged.cookie().getValue(),
            logged.token(),
            account.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", PENDING, DISABLED, UNVERIFIED, "wrong-password"})
  void ineligibleAndBadCredentialsShareFailureAndExpensiveProviderWork(String kind)
      throws Exception {
    String email = EMAIL;
    String password = PASSWORD;
    switch (kind) {
      case "unknown" -> email = "unknown@example.test";
      case PENDING ->
          jdbc.update(
              "UPDATE authentication_account SET status = 'pending', "
                  + "password_verifier = NULL, verified_at = NULL");
      case DISABLED -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case UNVERIFIED -> jdbc.update("UPDATE authentication_account SET verified_at = NULL");
      case "wrong-password" -> password = "wrong synthetic password";
      default -> throw new AssertionError("Unknown synthetic case.");
    }
    Browser browser = bootstrap();
    var response =
        mutate(LOGIN_PATH, browser.cookie(), browser.token(), form(email, password), FORM_MEDIA);
    assertError(response, 401, "AUTHENTICATION_FAILED");
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Authentication failed.\"}");
    verify(encoder).matches(org.mockito.ArgumentMatchers.eq(password), anyString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_security_event "
                    + "WHERE event = 'login' AND outcome = 'denied' AND reason = 'invalid-proof'",
                Integer.class))
        .isEqualTo(1);
    assertThat(read(STATUS_PATH, browser).getContentAsString())
        .isEqualTo("{\"state\":\"anonymous\"}");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spring_session " + "WHERE principal_name IS NOT NULL",
                Integer.class))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "email=a%40example.test&email=b%40example.test&password=p",
        "email=a%40example.test&%65mail=b%40example.test&password=p",
        "email=a%40example.test&password=p&extra=x",
        "email=a%40example.test",
        "email=a%40example.test&password=",
        "email=%20a%40example.test&password=p",
        "email=a%40example.test%20&password=p",
        "email=a%40example.test%0A&password=p",
        "email=a%40example.test&password=%C0%AF",
        "email=a%40example.test&password=%ED%A0%80",
        "email=a%40example.test&password=%GG",
        "email=a%40example.test&password=%４１",
        "email=a%40example.test&password=%",
        "email=a%40example.test&password=p&",
        "email=a%40example.test&password=a=b"
      })
  void rejectsFormsBeforeProviderOrUsernameTrimming(String raw) throws Exception {
    Browser browser = bootstrap();
    assertError(
        mutate(LOGIN_PATH, browser.cookie(), browser.token(), raw, FORM_MEDIA), 400, INVALID_CODE);
    org.mockito.Mockito.verifyNoInteractions(encoder);
  }

  @Test
  void rejectsQueryMediaAndScalarBoundsWithoutHashing() throws Exception {
    Browser browser = bootstrap();
    String valid = form(EMAIL, PASSWORD);
    assertError(
        mutate(
            "/api/v1/auth/login?email=other%40example.test",
            browser.cookie(), browser.token(), valid, FORM_MEDIA),
        400,
        INVALID_CODE);
    for (String type :
        List.of(
            JSON_MEDIA,
            "text/plain",
            "application/*",
            FORM_MEDIA + ";charset=ISO-8859-1",
            FORM_MEDIA + ";extra=value")) {
      assertError(
          mutate(LOGIN_PATH, browser.cookie(), browser.token(), valid, type), 400, INVALID_CODE);
    }
    for (String raw : List.of(valid + "x".repeat(16384), form(EMAIL, "x".repeat(129)))) {
      assertError(
          mutate(LOGIN_PATH, browser.cookie(), browser.token(), raw, FORM_MEDIA),
          400,
          INVALID_CODE);
    }
    org.mockito.Mockito.verifyNoInteractions(encoder);
  }

  @Test
  void unsafeRequestsRequireCurrentSingleHeaderCsrfAndExactOrigin() throws Exception {
    Browser browser = bootstrap();
    assertError(
        mutate(LOGIN_PATH, browser.cookie(), "stale", form(EMAIL, PASSWORD), FORM_MEDIA),
        403,
        DENIED_CODE);
    var missing =
        http.perform(
                postRequest(LOGIN_PATH)
                    .cookie(browser.cookie())
                    .contentType(FORM_MEDIA)
                    .content(form(EMAIL, PASSWORD)))
            .andReturn()
            .getResponse();
    assertError(missing, 403, DENIED_CODE);
    var duplicate =
        http.perform(
                postRequest(LOGIN_PATH)
                    .cookie(browser.cookie())
                    .header(CSRF_HEADER, browser.token(), browser.token())
                    .contentType(FORM_MEDIA)
                    .content(form(EMAIL, PASSWORD)))
            .andReturn()
            .getResponse();
    assertError(duplicate, 403, DENIED_CODE);
    var parameter =
        http.perform(
                postRequest(LOGIN_PATH)
                    .cookie(browser.cookie())
                    .contentType(FORM_MEDIA)
                    .content(form(EMAIL, PASSWORD) + "&_csrf=" + browser.token()))
            .andReturn()
            .getResponse();
    assertError(parameter, 400, INVALID_CODE);
    var cross =
        http.perform(
                post(LOGIN_PATH)
                    .header(ORIGIN_HEADER, "https://other.test")
                    .cookie(browser.cookie())
                    .header(CSRF_HEADER, browser.token())
                    .contentType(FORM_MEDIA)
                    .content(form(EMAIL, PASSWORD)))
            .andReturn()
            .getResponse();
    assertError(cross, 403, DENIED_CODE);
    org.mockito.Mockito.verifyNoInteractions(encoder);
  }

  @Test
  void nativeLogoutIsBodylessAndInvalidatesSessionCsrfAndCookie() throws Exception {
    Browser browser = login(bootstrap());
    assertError(
        mutate(LOGOUT_PATH, browser.cookie(), browser.token(), "{}", JSON_MEDIA),
        400,
        INVALID_CODE);
    assertError(
        mutate("/api/v1/auth/logout?target=x", browser.cookie(), browser.token(), null, null),
        400,
        INVALID_CODE);
    var response = mutate(LOGOUT_PATH, browser.cookie(), browser.token(), null, null);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("{\"completed\":true}");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains(COOKIE_NAME + "=", "Max-Age=0");
    assertThat(sessions.findById(browser.cookie().getValue())).isNull();
    assertError(read(BOOTSTRAP_PATH, browser), 401, REQUIRED_CODE);
    assertError(
        mutate(LOGOUT_PATH, browser.cookie(), browser.token(), null, null), 403, DENIED_CODE);
    Browser anonymous = bootstrap();
    assertThat(mutate(LOGOUT_PATH, anonymous.cookie(), anonymous.token(), null, null).getStatus())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"expiry", "version", DISABLED, UNVERIFIED, PENDING, "mode"})
  void currentAuthorityRejectsInvalidationWithoutNoAuthFallback(String invalidation)
      throws Exception {
    Browser browser = login(bootstrap());
    var identity = new AuthenticationIdentity(account, 1);
    var current = authority.find(browser.cookie().getValue(), identity).orElseThrow();
    switch (invalidation) {
      case "expiry" ->
          jdbc.update(
              "UPDATE spring_session SET expiry_time = 0, "
                  + "last_access_time = 0 WHERE session_id = ?",
              browser.cookie().getValue());
      case "version" -> jdbc.update("UPDATE authentication_account SET security_version = 2");
      case DISABLED -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case UNVERIFIED -> jdbc.update("UPDATE authentication_account SET verified_at = NULL");
      case PENDING ->
          jdbc.update(
              "UPDATE authentication_account SET status = 'pending', "
                  + "password_verifier = NULL, verified_at = NULL");
      case "mode" ->
          jdbc.update(
              "UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
      default -> throw new AssertionError("Unknown synthetic invalidation.");
    }
    try {
      assertThat(authority.find(browser.cookie().getValue(), identity)).isEmpty();
      assertThatThrownBy(
              () ->
                  transaction()
                      .executeWithoutResult(
                          status ->
                              authority.requireCurrent(
                                  account,
                                  current.sessionReference(),
                                  1,
                                  current.instance(),
                                  current.scope(),
                                  current.mode())))
          .isInstanceOf(AuthenticationRequiredException.class);
      assertError(read(BOOTSTRAP_PATH, browser), 401, REQUIRED_CODE);
      assertThat(read(STATUS_PATH, browser).getContentAsString())
          .isEqualTo("{\"state\":\"anonymous\"}");
    } finally {
      jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    }
  }

  @Test
  void accountSessionAndScopeSubstitutionDenyBeforeContent() throws Exception {
    Browser browser = login(bootstrap());
    var current =
        authority
            .find(browser.cookie().getValue(), new AuthenticationIdentity(account, 1))
            .orElseThrow();
    assertThat(
            authority.find(
                browser.cookie().getValue(), new AuthenticationIdentity(UUID.randomUUID(), 1)))
        .isEmpty();
    for (boolean foreignAccount : List.of(true, false)) {
      var context =
          SyntheticAccessContexts.authenticatedSession(
              new ServerInstanceId(current.instance().toString()),
              new SynchronizationScopeId(
                  (foreignAccount ? current.scope() : UUID.randomUUID()).toString()),
              ServerAccessMode.SINGLE_USER_AUTH,
              new AccountId((foreignAccount ? UUID.randomUUID() : account).toString()),
              current.sessionReference(),
              1);
      assertThatThrownBy(
              () -> transaction().executeWithoutResult(status -> scopes.requireScope(context)))
          .isInstanceOf(AuthenticationRequiredException.class);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_record", Integer.class))
        .isZero();
  }

  @Test
  void versionAndNativeRevocationRollbackTogetherAndCommitRevokesEverySession() throws Exception {
    Browser first = login(bootstrap());
    final Browser second = login(bootstrap());
    assertThat(sessions.findByPrincipalName(account.toString())).hasSize(2);
    transaction()
        .executeWithoutResult(
            status -> {
              bumpVersionAndRevoke();
              assertThat(sessions.findByPrincipalName(account.toString())).isEmpty();
              status.setRollbackOnly();
            });
    assertThat(sessions.findByPrincipalName(account.toString())).hasSize(2);
    assertThat(read(BOOTSTRAP_PATH, first).getStatus()).isEqualTo(200);
    transaction().executeWithoutResult(status -> bumpVersionAndRevoke());
    assertThat(sessions.findByPrincipalName(account.toString())).isEmpty();
    assertError(read(BOOTSTRAP_PATH, first), 401, REQUIRED_CODE);
    assertError(read(BOOTSTRAP_PATH, second), 401, REQUIRED_CODE);
  }

  @Test
  void delayedNativeLoginSaveAfterVersionChangeCannotRestoreAuthority() throws Exception {
    Browser anonymous = bootstrap();
    var verified = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object matched = invocation.callRealMethod();
              verified.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synthetic verification release timed out.");
              }
              return matched;
            })
        .when(encoder)
        .matches(PASSWORD, VERIFIER);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var login =
          executor.submit(
              () ->
                  mutate(
                      LOGIN_PATH,
                      anonymous.cookie(),
                      anonymous.token(),
                      form(EMAIL, PASSWORD),
                      FORM_MEDIA));
      try {
        assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
        transaction().executeWithoutResult(status -> bumpVersionAndRevoke());
      } finally {
        release.countDown();
      }
      var response = login.get(10, TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(200);
      Browser stale = new Browser(cookie(response), "unused");
      assertThat(sessions.findById(stale.cookie().getValue())).isNotNull();
      assertError(read(BOOTSTRAP_PATH, stale), 401, REQUIRED_CODE);
      assertThat(read(STATUS_PATH, stale).getContentAsString())
          .isEqualTo("{\"state\":\"anonymous\"}");
    }
  }

  @Test
  void emailCanonicalUniquenessAndPendingStorageConstraintsAreEnforced() {
    UUID scope =
        jdbc.queryForObject(
            "INSERT INTO technical_synchronization_scope "
                + "(server_instance_id) SELECT server_instance_id FROM "
                + "technical_server_installation "
                + "RETURNING synchronization_scope_id",
            UUID.class);
    try {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO authentication_account "
                          + "(server_instance_id, synchronization_scope_id, "
                          + "delivery_email, canonical_email, "
                          + "status, created_at) SELECT server_instance_id, ?, ?, ?, "
                          + "'pending', now() "
                          + "FROM technical_server_installation",
                      scope,
                      EMAIL.toUpperCase(java.util.Locale.ROOT),
                      EMAIL.toLowerCase(java.util.Locale.ROOT)))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> jdbc.update("UPDATE authentication_account SET status = 'pending'"))
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbc.update(
          "DELETE FROM technical_synchronization_scope WHERE synchronization_scope_id = ?", scope);
    }
  }

  @Test
  void nativeInactivityRenewsWithoutTheRetiredAbsoluteDeadline() throws Exception {
    Browser browser = login(bootstrap());
    long earlier = System.currentTimeMillis() - 10000;
    jdbc.update(
        "UPDATE spring_session SET creation_time = ?, last_access_time = ?, expiry_time = ? "
            + "WHERE session_id = ?",
        earlier - 13 * 60 * 60 * 1000,
        earlier,
        earlier + 1800000,
        browser.cookie().getValue());
    assertThat(read(STATUS_PATH, browser).getContentAsString()).contains("authenticated");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_access_time FROM spring_session WHERE session_id = ?",
                Long.class,
                browser.cookie().getValue()))
        .isGreaterThan(earlier);
  }

  @Test
  void requestAdmissionRejectsBeforeHashingAndRetiredRoutesCannotAuthenticate() throws Exception {
    Browser anonymous = bootstrap();
    when(attempts.admit(anyString(), anyString())).thenReturn(false);
    var throttled =
        mutate(
            LOGIN_PATH, anonymous.cookie(), anonymous.token(), form(EMAIL, PASSWORD), FORM_MEDIA);
    assertError(throttled, 429, "AUTHENTICATION_THROTTLED");
    assertThat(throttled.getHeader("Retry-After")).isEqualTo("60");
    for (String path :
        List.of(
            "/login/password",
            "/login/passkey",
            "/setup",
            "/step-up/password",
            "/recovery/operator",
            "/password-reset/request",
            "/password",
            "/sessions/revoke")) {
      assertThat(
              mutate("/api/v1/auth" + path, anonymous.cookie(), anonymous.token(), "{}", JSON_MEDIA)
                  .getStatus())
          .isIn(401, 403);
    }
    org.mockito.Mockito.verifyNoInteractions(encoder);
  }

  @Test
  void unavailableStorageHasFixedResponsesAndNeverClaimsAnonymous() throws Exception {
    Browser browser = login(bootstrap());
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("synthetic-private-canary"))
        .when(authority)
        .find(anyString(), org.mockito.ArgumentMatchers.any());
    var status = read(STATUS_PATH, browser);
    assertError(status, 503, "ACCESS_UNAVAILABLE");
    assertThat(status.getContentAsString()).doesNotContain("canary", "anonymous");
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("synthetic-private-canary"))
        .when(accounts)
        .findLogin(anyString());
    var response =
        mutate(LOGIN_PATH, browser.cookie(), browser.token(), form(EMAIL, PASSWORD), FORM_MEDIA);
    assertError(response, 503, "ACCESS_UNAVAILABLE");
    assertThat(response.getContentAsString()).doesNotContain("canary", EMAIL);
  }

  @Test
  void protectedTransactionFencesRevocationUntilContentBoundaryCompletes() throws Exception {
    Browser browser = login(bootstrap());
    var current =
        authority
            .find(browser.cookie().getValue(), new AuthenticationIdentity(account, 1))
            .orElseThrow();
    var context =
        SyntheticAccessContexts.authenticatedSession(
            new ServerInstanceId(current.instance().toString()),
            new SynchronizationScopeId(current.scope().toString()),
            ServerAccessMode.SINGLE_USER_AUTH,
            new AccountId(account.toString()),
            current.sessionReference(),
            1);
    var authorized = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var content =
          executor.submit(
              () ->
                  transaction()
                      .executeWithoutResult(
                          status -> {
                            scopes.lockForAcceptance(context);
                            authorized.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "Synthetic content release timed out.");
                              }
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException("Synthetic content interrupted.");
                            }
                          }));
      assertThat(authorized.await(10, TimeUnit.SECONDS)).isTrue();
      var revoking =
          executor.submit(
              () -> transaction().executeWithoutResult(status -> bumpVersionAndRevoke()));
      try {
        assertThatThrownBy(() -> revoking.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      content.get(10, TimeUnit.SECONDS);
      revoking.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(
              () -> transaction().executeWithoutResult(status -> scopes.requireScope(context)))
          .isInstanceOf(AuthenticationRequiredException.class);
    }
  }

  @Test
  void realHttpsPipelineEnforcesCookiesFormLoginAndChunkedBodyBounds() throws Exception {
    try (var client = trustedTestClient()) {
      var status =
          client.send(realRequest(STATUS_PATH).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(status.statusCode()).isEqualTo(200);
      assertThat(status.headers().firstValue("Set-Cookie")).isEmpty();
      var csrf =
          client.send(
              realRequest("/api/v1/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
      String setCookie = csrf.headers().firstValue("Set-Cookie").orElseThrow();
      assertThat(setCookie)
          .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/")
          .doesNotContain("Domain=", "Max-Age=");
      assertThat(csrf.headers().firstValue("Strict-Transport-Security"))
          .contains("max-age=31536000");
      String cookie = setCookie.split(";", 2)[0];
      String token = JSON.readTree(csrf.body()).get("token").asString();
      var large =
          client.send(
              realRequest(LOGIN_PATH)
                  .header(ORIGIN_HEADER, ORIGIN)
                  .header("Cookie", cookie)
                  .header(CSRF_HEADER, token)
                  .header("Content-Type", FORM_MEDIA)
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () ->
                              new java.io.ByteArrayInputStream(
                                  (form(EMAIL, PASSWORD) + "x".repeat(16384))
                                      .getBytes(StandardCharsets.UTF_8))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(large.statusCode()).isEqualTo(400);
      var login =
          client.send(
              realRequest(LOGIN_PATH)
                  .header(ORIGIN_HEADER, ORIGIN)
                  .header("Cookie", cookie)
                  .header(CSRF_HEADER, token)
                  .header("Content-Type", FORM_MEDIA)
                  .POST(HttpRequest.BodyPublishers.ofString(form(EMAIL, PASSWORD)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(login.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(login.body()).get("accountId").asString())
          .isEqualTo(account.toString());
      assertThat(login.headers().firstValue("Set-Cookie").orElseThrow())
          .doesNotStartWith(cookie + ";");
      assertThat(login.headers().firstValue("Location")).isEmpty();
    }
  }

  @Test
  void realServletEncodedAuthPathsCannotBypassInputGuards() throws Exception {
    Browser browser = bootstrap();
    try (var client = trustedTestClient()) {
      for (String alias : List.of("/api/v1/%61uth/login", "/api/v1/auth/%6cogin")) {
        for (String type : List.of(FORM_MEDIA, JSON_MEDIA)) {
          var response =
              client.send(
                  realRequest(alias + "?" + form(EMAIL, PASSWORD))
                      .header(ORIGIN_HEADER, ORIGIN)
                      .header("Cookie", COOKIE_NAME + "=" + browser.cookie().getValue())
                      .header(CSRF_HEADER, browser.token())
                      .header("Content-Type", type)
                      .POST(HttpRequest.BodyPublishers.ofString(form(EMAIL, PASSWORD)))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(response.statusCode()).isEqualTo(400);
          assertThat(response.headers().firstValue("Location")).isEmpty();
        }
      }
    }
    org.mockito.Mockito.verifyNoInteractions(encoder);
    assertThat(sessions.findByPrincipalName(account.toString())).isEmpty();
  }

  private void bumpVersionAndRevoke() {
    jdbc.queryForObject(
        "SELECT account_id FROM authentication_account WHERE account_id = ? " + "FOR UPDATE",
        UUID.class,
        account);
    jdbc.update(
        "UPDATE authentication_account SET security_version = security_version + 1 "
            + "WHERE account_id = ?",
        account);
    removal.removeAll(account);
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactions);
  }

  private Browser bootstrap() throws Exception {
    var response = http.perform(browserGet("/api/v1/auth/csrf")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return new Browser(cookie(response), body(response).get("token").asString());
  }

  private Browser login(Browser anonymous) throws Exception {
    var response =
        mutate(
            LOGIN_PATH, anonymous.cookie(), anonymous.token(), form(EMAIL, PASSWORD), FORM_MEDIA);
    assertThat(response.getStatus()).isEqualTo(200);
    Cookie cookie = cookie(response);
    var csrf = read("/api/v1/auth/csrf", new Browser(cookie, "unused"));
    return new Browser(cookie, body(csrf).get("token").asString());
  }

  private MockHttpServletResponse read(String path, Browser browser) throws Exception {
    return http.perform(browserGet(path).cookie(browser.cookie())).andReturn().getResponse();
  }

  private MockHttpServletResponse mutate(
      String path, Cookie cookie, String csrf, String raw, String type) throws Exception {
    var request = postRequest(path).cookie(cookie).header(CSRF_HEADER, csrf);
    if (type != null) {
      request.contentType(type);
    }
    if (raw != null) {
      request.content(raw);
    }
    return http.perform(request).andReturn().getResponse();
  }

  private static MockHttpServletRequestBuilder browserGet(String path) {
    return get(path)
        .secure(true)
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
  }

  private static MockHttpServletRequestBuilder postRequest(String path) {
    return post(path)
        .secure(true)
        .header(ORIGIN_HEADER, ORIGIN)
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
  }

  private static String form(String email, String password) {
    return "email="
        + URLEncoder.encode(email, StandardCharsets.UTF_8)
        + "&password="
        + URLEncoder.encode(password, StandardCharsets.UTF_8);
  }

  private static Cookie cookie(MockHttpServletResponse response) {
    String header = response.getHeader(HttpHeaders.SET_COOKIE);
    return new Cookie(COOKIE_NAME, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
  }

  private static tools.jackson.databind.JsonNode body(MockHttpServletResponse response)
      throws Exception {
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getHeader("Location")).isNull();
    return JSON.readTree(response.getContentAsString());
  }

  private static void assertError(MockHttpServletResponse response, int status, String code)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(body(response).get("code").asString()).isEqualTo(code);
  }

  private HttpRequest.Builder realRequest(String path) {
    return HttpRequest.newBuilder(URI.create(ORIGIN + ":" + port + path))
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
  }

  private static Path createTlsMaterial() {
    try {
      Path directory = Files.createTempDirectory("hortinis-r2-tls-");
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "hortinis-test",
                  "-keyalg",
                  "EC",
                  "-groupname",
                  "secp256r1",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1",
                  "-validity",
                  "1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  directory.resolve(KEYSTORE_FILE).toString(),
                  "-storepass",
                  TLS_PASSWORD,
                  "-keypass",
                  TLS_PASSWORD,
                  "-noprompt")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("Synthetic TLS setup failed.");
      }
      return directory;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ExceptionInInitializerError(exception);
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static HttpClient trustedTestClient() {
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(TLS_DIRECTORY.resolve(KEYSTORE_FILE))) {
        store.load(input, TLS_PASSWORD.toCharArray());
      }
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      var context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return HttpClient.newBuilder().sslContext(context).build();
    } catch (GeneralSecurityException | IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @AfterAll
  static void removeSyntheticTls() throws IOException {
    Files.deleteIfExists(TLS_DIRECTORY.resolve(KEYSTORE_FILE));
    Files.delete(TLS_DIRECTORY);
  }

  private record Browser(Cookie cookie, String token) {
    @Override
    public String toString() {
      return "SyntheticBrowser[redacted]";
    }
  }

  /** Test source only: selects the production graph without the production admission bean. */
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
  @ComponentScan(
      basePackages = "com.hortinis.sync",
      excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {HortinisSyncApplication.class, SynchronizationAccessConfiguration.class}),
        @ComponentScan.Filter(
            type = FilterType.ANNOTATION,
            classes = org.springframework.boot.test.context.TestConfiguration.class)
      })
  static class NativeFixtureConfiguration {
    @Bean
    ServerAccessMode serverAccessMode() {
      return ServerAccessMode.SINGLE_USER_AUTH;
    }

    @Bean
    @DependsOnDatabaseInitialization
    SynchronizationAccessResolver synchronizationAccessResolver(
        ServerInstallationPersistence installation,
        CurrentAuthenticationAuthorityPersistence current) {
      installation.initializeMode(ServerAccessMode.SINGLE_USER_AUTH);
      return new AuthenticatedSynchronizationAccessResolver(current);
    }
  }
}
