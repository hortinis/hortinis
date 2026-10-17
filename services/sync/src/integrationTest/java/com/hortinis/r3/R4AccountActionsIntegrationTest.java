package com.hortinis.r3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.HortinisSyncApplication;
import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.authentication.AccountActionService;
import com.hortinis.sync.authentication.ActionMailAdmission;
import com.hortinis.sync.authentication.ActionMailDelivery;
import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.CryptoWorkLimiter;
import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.authentication.PasswordVerifier;
import com.hortinis.sync.authentication.SmtpActionMailDelivery;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.configuration.SynchronizationAccessConfiguration;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Fresh R4 servlet, password, transaction and native-session evidence with synthetic mail only. */
@Testcontainers
@SpringBootTest(
    classes = R4AccountActionsIntegrationTest.NativeFixtureConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class R4AccountActionsIntegrationTest {
  private static final String CURRENT_PASSWORD_FIELD = "currentPassword";
  private static final String SCOPE_STATE = "scope";
  private static final String EMAIL_FIELD = "email";
  private static final String ACCEPTED = "accepted";

  private static final String COMPLETED = "completed";
  private static final String PRECONDITION = "AUTHENTICATION_PRECONDITION_FAILED";
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String PASSWORD_FIELD = "newPassword";
  private static final String INVALID = "INVALID_REQUEST";
  private static final String DISABLED = "disabled";
  private static final String PENDING = "pending";
  private static final String UNVERIFIED = "unverified";
  private static final String UNKNOWN = "unknown";
  private static final String QUOTA = "quota";
  private static final String JSON_MEDIA = "application/json";
  private static final String TOKEN_FIELD = "token";

  private static final String ORIGIN = "https://localhost";
  private static final String EMAIL = "Gardener+tag@Example.test";
  private static final String PASSWORD = " synthetic+phrase&=🌱 ";
  private static final String NEW_PASSWORD = " another private phrase 🌷 ";
  private static final String ACCEPT = "/api/v1/auth/invitations/accept";
  private static final String REQUEST = "/api/v1/auth/password-reset/request";
  private static final String RESET = "/api/v1/auth/password-reset/complete";
  private static final String CHANGE = "/api/v1/auth/password";
  private static final String STATUS = "/api/v1/auth/session";
  private static final String COOKIE_NAME = "__Host-hortinis-session";
  private static final String TLS_PASSWORD = "r4-synthetic-tls-password";
  private static final String KEYSTORE_FILE = "runtime.p12";
  private static final Path TLS_DIRECTORY = createTlsMaterial();
  private static final String VERIFIER =
      new PasswordVerifier(new CryptoWorkLimiter()).encode(PASSWORD);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private MockMvc http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private R4Clock clock;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private ActionTokenPersistence tokens;
  @Autowired private AccountActionService actions;
  @Autowired private JdbcIndexedSessionRepository sessions;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private PasswordVerifier passwords;

  @Autowired private CurrentAuthenticationAuthorityPersistence authority;
  @MockitoBean private AuthenticationAttemptLimiter attempts;
  @MockitoBean private ActionMailAdmission mail;
  @MockitoBean private ActionMailDelivery delivery;
  @LocalServerPort private int port;
  private UUID account;
  private ActionMailAdmission realMail;
  private final AtomicReference<ActionTokenPersistence.IssuedToken> lastMail =
      new AtomicReference<>();

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
  void freshSyntheticAccount() {
    clock.reset();
    jdbc.execute("DROP TRIGGER IF EXISTS synthetic_revoke_failure ON spring_session");
    jdbc.update("DELETE FROM spring_session");
    jdbc.update("DELETE FROM technical_exchange_expectation");
    jdbc.update("DELETE FROM authentication_account");
    jdbc.update("DELETE FROM authentication_security_event");
    jdbc.update("UPDATE technical_synchronization_scope SET technical_default = true");
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    account =
        jdbc.queryForObject(
            "INSERT INTO authentication_account (server_instance_id, synchronization_scope_id, "
                + "delivery_email, canonical_email, status, password_verifier, "
                + "created_at, verified_at) "
                + "SELECT server_instance_id, synchronization_scope_id, ?, ?, "
                + "'active', ?, now(), now() "
                + "FROM technical_synchronization_scope WHERE technical_default "
                + "RETURNING account_id",
            UUID.class,
            EMAIL,
            EmailAddress.canonical(EMAIL),
            VERIFIER);
    realMail = new ActionMailAdmission(Clock.systemUTC());
    when(mail.reserve(anyString())).thenAnswer(call -> realMail.reserve(call.getArgument(0)));
    var realAttempts = new AuthenticationAttemptLimiter(Clock.systemUTC());
    when(attempts.admit(anyString(), anyString()))
        .thenAnswer(call -> realAttempts.admit(call.getArgument(0), call.getArgument(1)));
    when(attempts.admitSource(anyString()))
        .thenAnswer(call -> realAttempts.admitSource(call.getArgument(0)));
    when(delivery.deliver(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              lastMail.set(call.getArgument(0));
              return true;
            });
    lastMail.set(null);
  }

  @Test
  void invitationActivatesBoundAccountWithoutLoginAndLostSuccessCannotReplay(CapturedOutput output)
      throws Exception {
    makePending();
    var issued = issue(ActionTokenPurpose.INVITATION);
    Browser browser = bootstrap();
    success(postJson(ACCEPT, browser, completion(issued.secret(), NEW_PASSWORD)), 200, COMPLETED);
    assertThat(jdbc.queryForObject("SELECT status FROM authentication_account", String.class))
        .isEqualTo("active");
    assertThat(
            jdbc.queryForObject(
                "SELECT verified_at IS NOT NULL FROM authentication_account", Boolean.class))
        .isTrue();
    assertThat(passwords.matches(NEW_PASSWORD, storedVerifier())).isTrue();
    assertThat(version()).isEqualTo(1);
    anonymous(browser);
    assertThat(indexed()).isZero();
    error(postJson(ACCEPT, browser, completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    assertThat(output.getAll())
        .doesNotContain(EMAIL, issued.secret(), NEW_PASSWORD, account.toString());
  }

  @Test
  void resetRevokesAllNativeSessionsAndStaleSavesCannotRestoreAuthority() throws Exception {
    Browser first = login(bootstrap());
    final Browser second = login(bootstrap());
    final org.springframework.session.Session stale = sessions.findById(first.cookie().getValue());
    var issued = issue(ActionTokenPurpose.RESET);
    Browser anonymous = bootstrap();
    success(postJson(RESET, anonymous, completion(issued.secret(), NEW_PASSWORD)), 200, COMPLETED);
    assertThat(indexed()).isZero();
    assertThat(version()).isEqualTo(2);
    assertThat(passwords.matches(NEW_PASSWORD, storedVerifier())).isTrue();
    try {
      stale.setAttribute(
          "hortinis.csrf",
          new org.springframework.security.web.csrf.DefaultCsrfToken(
              CSRF_HEADER, "_csrf", UUID.randomUUID().toString()));
      save(stale);
    } catch (org.springframework.dao.DataAccessException expected) {
      // The native repository may reject a delayed attribute insert after row deletion.
    }
    anonymous(first);
    anonymous(second);
    anonymous(anonymous);
    error(postJson(RESET, anonymous, completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
  }

  @Test
  void passwordChangeRequiresCurrentPasswordAndRevokesCallerAndOtherSessions() throws Exception {
    Browser first = login(bootstrap());
    final Browser second = login(bootstrap());
    var issued = issue(ActionTokenPurpose.RESET);
    Browser current = refresh(first);
    error(
        postJson(
            CHANGE,
            current,
            JSON.writeValueAsString(
                Map.of(CURRENT_PASSWORD_FIELD, "wrong", PASSWORD_FIELD, NEW_PASSWORD))),
        401,
        "AUTHENTICATION_FAILED");
    assertThat(tokenCount()).isEqualTo(1);
    success(
        postJson(
            CHANGE,
            current,
            JSON.writeValueAsString(
                Map.of(CURRENT_PASSWORD_FIELD, PASSWORD, PASSWORD_FIELD, NEW_PASSWORD))),
        200,
        COMPLETED);
    assertThat(indexed()).isZero();
    assertThat(version()).isEqualTo(2);
    assertThat(tokenCount()).isZero();
    anonymous(first);
    anonymous(second);
    assertThat(passwords.matches(NEW_PASSWORD, storedVerifier())).isTrue();
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void invalidPasswordDoesNotConsumeAndReplacementExpiryReplayAreUniform(ActionTokenPurpose purpose)
      throws Exception {
    if (purpose == ActionTokenPurpose.INVITATION) {
      makePending();
    }
    var old = issue(purpose);
    var issued = issue(purpose);
    Browser browser = bootstrap();
    String path = path(purpose);
    error(
        postJson(path, browser, completion(issued.secret(), "hortinis insecure password")),
        400,
        INVALID);
    assertThat(tokenCount()).isEqualTo(1);
    error(postJson(path, browser, completion(old.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    jdbc.update(
        "UPDATE authentication_action_token SET created_at = now() - "
            + "interval '2 minutes', expires_at = now() - interval '1 minute'");
    error(postJson(path, browser, completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    assertThat(storedVerifier())
        .isEqualTo(purpose == ActionTokenPurpose.INVITATION ? null : VERIFIER);
  }

  @ParameterizedTest
  @ValueSource(strings = {DISABLED, PENDING, UNVERIFIED, SCOPE_STATE, "mode"})
  void resetCompletionRechecksCurrentEligibilityAndOwnership(String invalid) throws Exception {
    var issued = issue(ActionTokenPurpose.RESET);
    invalidate(invalid);
    Browser browser = bootstrap();
    error(postJson(RESET, browser, completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    assertThat(tokenCount()).isEqualTo(1);
    assertThat(version()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {DISABLED, "active", SCOPE_STATE, "mode"})
  void invitationCannotActivateSubstitutedOrIneligibleAccount(String invalid) throws Exception {
    makePending();
    var issued = issue(ActionTokenPurpose.INVITATION);
    if (invalid.equals("active")) {
      jdbc.update(
          "UPDATE authentication_account SET status = 'active', verified_at "
              + "= now(), password_verifier = ?",
          VERIFIER);
    } else {
      invalidate(invalid);
    }
    error(
        postJson(ACCEPT, bootstrap(), completion(issued.secret(), NEW_PASSWORD)),
        409,
        PRECONDITION);
    assertThat(tokenCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void concurrentCompletionHasOneWinner(ActionTokenPurpose purpose) throws Exception {
    if (purpose == ActionTokenPurpose.INVITATION) {
      makePending();
    }
    var issued = issue(purpose);
    Browser first = bootstrap();
    final Browser second = bootstrap();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                start.await();
                return postJson(path(purpose), first, completion(issued.secret(), NEW_PASSWORD))
                    .getStatus();
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return postJson(path(purpose), second, completion(issued.secret(), NEW_PASSWORD))
                    .getStatus();
              });
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(tokenCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"reset", "change"})
  void failedNativeSessionDeletionRollsBackVerifierVersionTokensAndAllSessions(
      String operation, CapturedOutput output) throws Exception {
    final Browser browser = refresh(login(bootstrap()));
    login(bootstrap());
    var issued = issue(ActionTokenPurpose.RESET);
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION synthetic_revoke_failure() RETURNS "
            + "trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic "
            + "diagnostic canary'; END $$");
    jdbc.execute(
        "CREATE TRIGGER synthetic_revoke_failure BEFORE DELETE ON "
            + "spring_session FOR EACH ROW EXECUTE FUNCTION "
            + "synthetic_revoke_failure()");
    var response =
        operation.equals("reset")
            ? postJson(RESET, bootstrap(), completion(issued.secret(), NEW_PASSWORD))
            : postJson(
                CHANGE,
                browser,
                JSON.writeValueAsString(
                    Map.of(CURRENT_PASSWORD_FIELD, PASSWORD, PASSWORD_FIELD, NEW_PASSWORD)));
    error(response, 503, "ACCESS_UNAVAILABLE");
    assertThat(version()).isEqualTo(1);
    assertThat(storedVerifier()).isEqualTo(VERIFIER);
    assertThat(tokenCount()).isEqualTo(1);
    assertThat(indexed()).isEqualTo(2);
    assertThat(output.getAll())
        .doesNotContain("synthetic diagnostic canary", EMAIL, issued.secret(), NEW_PASSWORD);
    jdbc.execute("DROP TRIGGER synthetic_revoke_failure ON spring_session");
    success(
        postJson(RESET, bootstrap(), completion(issued.secret(), NEW_PASSWORD)), 200, COMPLETED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"eligible", UNKNOWN, PENDING, DISABLED, UNVERIFIED, QUOTA, "smtp"})
  void resetAcknowledgementIsGenericAcrossEligibilityQuotaAndDeliveryFailure(
      String branch, CapturedOutput output) throws Exception {
    if (Set.of(PENDING, DISABLED, UNVERIFIED).contains(branch)) {
      invalidate(branch);
    }
    String email = branch.equals(UNKNOWN) ? "Unknown@Example.test" : EMAIL;
    if (branch.equals(QUOTA)) {
      try (var ignored = realMail.reserve(EmailAddress.canonical(email))) {
        assertThat(ignored.allowed()).isTrue();
      }
    }
    if (branch.equals("smtp")) {
      when(delivery.deliver(org.mockito.ArgumentMatchers.any())).thenReturn(false);
    }
    var response =
        postJson(REQUEST, bootstrap(), JSON.writeValueAsString(Map.of(EMAIL_FIELD, email)));
    success(response, 202, ACCEPTED);
    assertThat(tokenCount()).isEqualTo(Set.of("eligible", "smtp").contains(branch) ? 1 : 0);
    assertThat(output.getAll()).doesNotContain(email, PASSWORD, account.toString());
  }

  @Test
  void resetUsesStoredRecipientAfterAdmissionAndRealLoopbackSmtp() throws Exception {
    try (var smtp = new LocalSmtpFixture(false, false)) {
      var adapter = new SmtpActionMailDelivery(smtp.sender(), "Hortinis@Example.test", ORIGIN);
      when(delivery.deliver(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(call.<ActionTokenPersistence.IssuedToken>getArgument(0).recipient())
                    .isEqualTo(EMAIL);
                return adapter.deliver(call.getArgument(0));
              });
      success(
          postJson(REQUEST, bootstrap(), "{\"email\":\"GARDENER+TAG@EXAMPLE.TEST\"}"),
          202,
          ACCEPTED);
      assertThat(smtp.message()).contains("/reset-password#token=");
    }
  }

  @Test
  void publicSourceGlobalLimitsRemainFixedAndIndependentOfEligibility() throws Exception {
    Browser browser = bootstrap();
    when(attempts.admit(anyString(), anyString())).thenReturn(false);
    var denied = postJson(REQUEST, browser, "{\"email\":\"Unknown@Example.test\"}");
    error(denied, 429, "AUTHENTICATION_THROTTLED");
    assertThat(denied.getHeader("Retry-After")).isEqualTo("60");
    assertThat(tokenCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {ACCEPT, REQUEST, RESET, CHANGE})
  void nativeCsrfOriginNoStoreAndAuthenticationContractsAreEnforced(String path) throws Exception {
    Browser browser = bootstrap();
    var noCsrf =
        http.perform(
                postRequest(path).cookie(browser.cookie()).contentType(JSON_MEDIA).content("{}"))
            .andReturn()
            .getResponse();
    error(noCsrf, 403, "ACCESS_DENIED");
    var crossOrigin =
        http.perform(
                postRequest(path)
                    .header("Origin", "https://other.example.test")
                    .cookie(browser.cookie())
                    .header(CSRF_HEADER, browser.token())
                    .contentType(JSON_MEDIA)
                    .content("{}"))
            .andReturn()
            .getResponse();
    error(crossOrigin, 403, "ACCESS_DENIED");
    if (path.equals(CHANGE)) {
      error(postJson(path, browser, "{}"), 401, "AUTHENTICATION_REQUIRED");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"email\":null}",
        "{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}",
        "{\"email\":\"a@example.test\",\"scope\":\"arbitrary\"}",
        "{\"email\":{\"nested\":{}}}",
        "{\"email\":\"a@example.test\"} {}",
        "{\"email\":\" a@example.test\"}"
      })
  void closedJsonRejectsMalformedDuplicateUnknownNestedAndTrailingInput(String input)
      throws Exception {
    error(postJson(REQUEST, bootstrap(), input), 400, INVALID);
    assertThat(tokenCount()).isZero();
  }

  @Test
  void wrongPurposeAndCallerSelectedAccountScopeAreRejectedWithoutConsumption() throws Exception {
    var issued = issue(ActionTokenPurpose.RESET);
    Browser browser = bootstrap();
    error(postJson(ACCEPT, browser, completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    error(
        postJson(
            RESET,
            browser,
            JSON.writeValueAsString(
                Map.of(
                    TOKEN_FIELD,
                    issued.secret(),
                    PASSWORD_FIELD,
                    NEW_PASSWORD,
                    "accountId",
                    UUID.randomUUID().toString()))),
        400,
        INVALID);
    assertThat(tokenCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {ACCEPT, RESET, "/accept-invitation", "/reset-password"})
  void openingLinksIsInert(String path) throws Exception {
    var issued = issue(ActionTokenPurpose.RESET);
    http.perform(browserGet(path));
    assertThat(tokenCount()).isEqualTo(1);
    assertThat(storedVerifier()).isEqualTo(VERIFIER);
    assertThat(version()).isEqualTo(1);
  }

  @Test
  void invitationRollbackRestoresPendingAccountAndUsableToken() throws Exception {
    makePending();
    var issued = issue(ActionTokenPurpose.INVITATION);
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION synthetic_activation_failure() "
            + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION "
            + "'synthetic activation failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER synthetic_activation_failure AFTER UPDATE ON "
            + "authentication_account FOR EACH ROW EXECUTE FUNCTION "
            + "synthetic_activation_failure()");
    Browser browser = bootstrap();
    try {
      error(
          postJson(ACCEPT, browser, completion(issued.secret(), NEW_PASSWORD)),
          503,
          "ACCESS_UNAVAILABLE");
      assertThat(jdbc.queryForObject("SELECT status FROM authentication_account", String.class))
          .isEqualTo(PENDING);
      assertThat(storedVerifier()).isNull();
      assertThat(version()).isEqualTo(1);
      assertThat(tokenCount()).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER synthetic_activation_failure ON authentication_account");
    }
    success(postJson(ACCEPT, browser, completion(issued.secret(), NEW_PASSWORD)), 200, COMPLETED);
  }

  @Test
  void preparedResetCannotOverwriteChangedCredentialSnapshot() throws Exception {
    var issued = issue(ActionTokenPurpose.RESET);
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              Object result = call.callRealMethod();
              jdbc.update(
                  "UPDATE authentication_account SET security_version = security_version + 1");
              return result;
            })
        .when(passwords)
        .encode(NEW_PASSWORD);
    error(
        postJson(RESET, bootstrap(), completion(issued.secret(), NEW_PASSWORD)), 409, PRECONDITION);
    assertThat(storedVerifier()).isEqualTo(VERIFIER);
    assertThat(version()).isEqualTo(2);
    assertThat(tokenCount()).isEqualTo(1);
  }

  @Test
  void concurrentLoginSavedAfterCommittedResetCannotRestoreAuthority() throws Exception {
    Browser before = bootstrap();
    Browser resetBrowser = bootstrap();
    var issued = issue(ActionTokenPurpose.RESET);
    var checked = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            call -> {
              Object result = call.callRealMethod();
              checked.countDown();
              if (!resume.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synthetic verification timeout.");
              }
              return result;
            })
        .when(passwords)
        .matches(PASSWORD, VERIFIER);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var delayed = executor.submit(() -> login(before));
      assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        success(
            postJson(RESET, resetBrowser, completion(issued.secret(), NEW_PASSWORD)),
            200,
            COMPLETED);
      } finally {
        resume.countDown();
      }
      Browser stale = delayed.get(15, TimeUnit.SECONDS);
      anonymous(stale);
      var protectedResponse =
          http.perform(browserGet("/api/v1/sync/bootstrap").cookie(stale.cookie()))
              .andReturn()
              .getResponse();
      assertThat(protectedResponse.getStatus()).isEqualTo(401);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/json;profile=arbitrary",
        "text/plain",
        "application/json;charset=UTF-16"
      })
  void unsupportedMediaCannotReachAccountLookup(String type) throws Exception {
    Browser browser = bootstrap();
    var response =
        http.perform(
                postRequest(REQUEST)
                    .cookie(browser.cookie())
                    .header(CSRF_HEADER, browser.token())
                    .contentType(type)
                    .content("{\"email\":\"a@example.test\"}"))
            .andReturn()
            .getResponse();
    error(response, 400, INVALID);
  }

  @Test
  void rawByteBoundsMalformedUtf8SurrogatesAndNoncanonicalTokensFailClosed() throws Exception {
    Browser browser = bootstrap();
    for (byte[] bytes :
        List.of(
            new byte[] {(byte) 0xc3, 0x28},
            new byte[16385],
            "{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}"
                .getBytes(StandardCharsets.UTF_8))) {
      error(
          http.perform(
                  postRequest(REQUEST)
                      .cookie(browser.cookie())
                      .header(CSRF_HEADER, browser.token())
                      .contentType(JSON_MEDIA)
                      .content(bytes))
              .andReturn()
              .getResponse(),
          400,
          INVALID);
    }
    for (String token : List.of("bad", "a".repeat(44), "a".repeat(42) + "B")) {
      error(postJson(RESET, browser, completion(token, NEW_PASSWORD)), 400, INVALID);
    }
    String loneSurrogate =
        "{\"token\":\""
            + AuthenticationSecrets.randomValue()
            + "\",\"newPassword\":\"long synthetic phrase "
            + "\\ud800"
            + "\"}";
    error(postJson(RESET, browser, loneSurrogate), 400, INVALID);
  }

  @Test
  void completePublicHttpsResponseSamplesRemainGenericWithoutTimingClaim() throws Exception {
    try (var client = trustedTestClient()) {
      var csrf =
          client.send(
              realRequest("/api/v1/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
      String cookie = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      String token = JSON.readTree(csrf.body()).get(TOKEN_FIELD).asString();
      for (String branch : List.of(ACCEPTED, "refused", UNKNOWN, QUOTA)) {
        realMail = new ActionMailAdmission(Clock.systemUTC());
        String email = branch.equals(UNKNOWN) ? "Unknown@Example.test" : EMAIL;
        if (branch.equals(QUOTA)) {
          try (var permit = realMail.reserve(EmailAddress.canonical(email))) {
            assertThat(permit.allowed()).isTrue();
          }
        }
        try (var smtp = new LocalSmtpFixture(false, branch.equals("refused"))) {
          var adapter = new SmtpActionMailDelivery(smtp.sender(), "Hortinis@Example.test", ORIGIN);
          org.mockito.Mockito.doAnswer(call -> adapter.deliver(call.getArgument(0)))
              .when(delivery)
              .deliver(org.mockito.ArgumentMatchers.any());
          long started = System.nanoTime();
          var response =
              client.send(
                  realRequest(REQUEST)
                      .header("Origin", ORIGIN)
                      .header("Cookie", cookie)
                      .header(CSRF_HEADER, token)
                      .header("Content-Type", JSON_MEDIA)
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              JSON.writeValueAsString(Map.of(EMAIL_FIELD, email))))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          final long micros = (System.nanoTime() - started) / 1000;
          assertThat(response.statusCode()).isEqualTo(202);
          assertThat(response.body()).isEqualTo("{\"accepted\":true}");
          assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
          System.out.println("R4 synthetic public reset " + branch + " microseconds=" + micros);
        }
      }
    }
  }

  private HttpRequest.Builder realRequest(String path) {
    return HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
        .timeout(Duration.ofSeconds(15))
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void exactExpiryCompletionFailsWhileImmediatelyBeforeRemainsUsable(ActionTokenPurpose purpose)
      throws Exception {
    if (purpose == ActionTokenPurpose.INVITATION) {
      makePending();
    }
    var before = issue(purpose);
    Browser browser = bootstrap();
    clock.at(before.expiresAt().minusNanos(1000));
    success(
        postJson(path(purpose), browser, completion(before.secret(), NEW_PASSWORD)),
        200,
        COMPLETED);
    clock.reset();
    if (purpose == ActionTokenPurpose.INVITATION) {
      makePending();
    }
    var expired = issue(purpose);
    String previous = storedVerifier();
    clock.at(expired.expiresAt());
    error(
        postJson(path(purpose), browser, completion(expired.secret(), NEW_PASSWORD)),
        409,
        PRECONDITION);
    assertThat(storedVerifier()).isEqualTo(previous);
  }

  @ParameterizedTest
  @ValueSource(strings = {DISABLED, PENDING, UNVERIFIED, SCOPE_STATE, "version"})
  void passwordChangeRejectsStaleOrIneligibleNativeAuthority(String invalid) throws Exception {
    Browser browser = refresh(login(bootstrap()));
    if (invalid.equals("version")) {
      jdbc.update("UPDATE authentication_account SET security_version = security_version + 1");
    } else {
      invalidate(invalid);
    }
    String before = storedVerifier();
    long version = version();
    error(
        postJson(
            CHANGE,
            browser,
            JSON.writeValueAsString(
                Map.of(CURRENT_PASSWORD_FIELD, PASSWORD, PASSWORD_FIELD, NEW_PASSWORD))),
        401,
        "AUTHENTICATION_REQUIRED");
    assertThat(storedVerifier()).isEqualTo(before);
    assertThat(version()).isEqualTo(version);
  }

  @Test
  void mailCapacityIsReservedBeforeEligibilityLookupForEveryAddress() throws Exception {
    Browser browser = bootstrap();
    try (var first = realMail.reserve("First@Example.test");
        var second = realMail.reserve("Second@Example.test")) {
      assertThat(first.allowed()).isTrue();
      assertThat(second.allowed()).isTrue();
      for (String email : List.of(EMAIL, "Unknown@Example.test")) {
        error(
            postJson(REQUEST, browser, JSON.writeValueAsString(Map.of(EMAIL_FIELD, email))),
            429,
            "AUTHENTICATION_THROTTLED");
      }
      assertThat(tokenCount()).isZero();
    }
    success(
        postJson(REQUEST, browser, JSON.writeValueAsString(Map.of(EMAIL_FIELD, EMAIL))),
        202,
        ACCEPTED);
    assertThat(tokenCount()).isEqualTo(1);
  }

  private void invalidate(String kind) {
    switch (kind) {
      case PENDING -> makePending();
      case DISABLED -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case UNVERIFIED -> jdbc.update("UPDATE authentication_account SET verified_at = NULL");
      case SCOPE_STATE ->
          jdbc.update("UPDATE technical_synchronization_scope SET technical_default = false");
      case "mode" ->
          jdbc.update(
              "UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
      default -> throw new IllegalArgumentException();
    }
  }

  @SuppressWarnings("unchecked")
  private void save(org.springframework.session.Session session) {
    ((org.springframework.session.SessionRepository<org.springframework.session.Session>)
            (org.springframework.session.SessionRepository<?>) sessions)
        .save(session);
  }

  private void makePending() {
    jdbc.update(
        "UPDATE authentication_account SET status = 'pending', "
            + "password_verifier = NULL, verified_at = NULL");
  }

  private ActionTokenPersistence.IssuedToken issue(ActionTokenPurpose purpose) {
    return new TransactionTemplate(transactions).execute(status -> tokens.issue(account, purpose));
  }

  private long version() {
    return jdbc.queryForObject("SELECT security_version FROM authentication_account", Long.class);
  }

  private String storedVerifier() {
    return jdbc.queryForObject(
        "SELECT password_verifier FROM authentication_account", String.class);
  }

  private int tokenCount() {
    return jdbc.queryForObject("SELECT count(*) FROM authentication_action_token", Integer.class);
  }

  private int indexed() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL", Integer.class);
  }

  private static String path(ActionTokenPurpose purpose) {
    return purpose == ActionTokenPurpose.INVITATION ? ACCEPT : RESET;
  }

  private static String completion(String secret, String password) {
    return JSON.writeValueAsString(Map.of(TOKEN_FIELD, secret, PASSWORD_FIELD, password));
  }

  private Browser bootstrap() throws Exception {
    var response = http.perform(browserGet("/api/v1/auth/csrf")).andReturn().getResponse();
    return new Browser(
        cookie(response), JSON.readTree(response.getContentAsString()).get(TOKEN_FIELD).asString());
  }

  private Browser refresh(Browser browser) throws Exception {
    var response =
        http.perform(browserGet("/api/v1/auth/csrf").cookie(browser.cookie()))
            .andReturn()
            .getResponse();
    return new Browser(
        browser.cookie(), JSON.readTree(response.getContentAsString()).get(TOKEN_FIELD).asString());
  }

  private Browser login(Browser browser) throws Exception {
    var response =
        http.perform(
                postRequest("/api/v1/auth/login")
                    .cookie(browser.cookie())
                    .header(CSRF_HEADER, browser.token())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(
                        "email="
                            + URLEncoder.encode(EMAIL, StandardCharsets.UTF_8)
                            + "&password="
                            + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return new Browser(cookie(response), browser.token());
  }

  private void anonymous(Browser browser) throws Exception {
    var response =
        http.perform(browserGet(STATUS).cookie(browser.cookie())).andReturn().getResponse();
    assertThat(JSON.readTree(response.getContentAsString()).get("state").asString())
        .isEqualTo("anonymous");
  }

  private MockHttpServletResponse postJson(String path, Browser browser, String json)
      throws Exception {
    return http.perform(
            postRequest(path)
                .cookie(browser.cookie())
                .header(CSRF_HEADER, browser.token())
                .contentType(JSON_MEDIA)
                .content(json))
        .andReturn()
        .getResponse();
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
        .header("Origin", ORIGIN)
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
  }

  private static Cookie cookie(MockHttpServletResponse response) {
    String header = response.getHeader("Set-Cookie");
    return new Cookie(COOKIE_NAME, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
  }

  private static void success(MockHttpServletResponse response, int status, String key)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).isEqualTo("{\"" + key + "\":true}");
  }

  private static void error(MockHttpServletResponse response, int status, String code)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(JSON.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
  }

  private record Browser(Cookie cookie, String token) {
    @Override
    public String toString() {
      return "R4Browser[redacted]";
    }
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
    @org.springframework.context.annotation.Primary
    R4Clock r4Clock() {
      return new R4Clock();
    }

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

  /** Controlled action expiry, initially real UTC so context startup cleanup remains valid. */
  static final class R4Clock extends Clock {
    private volatile Clock selected = Clock.systemUTC();

    private void reset() {
      selected = Clock.systemUTC();
    }

    private void at(java.time.Instant instant) {
      selected = Clock.fixed(instant, java.time.ZoneOffset.UTC);
    }

    @Override
    public java.time.Instant instant() {
      return selected.instant();
    }

    @Override
    public java.time.ZoneId getZone() {
      return selected.getZone();
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return selected.withZone(zone);
    }
  }
}
