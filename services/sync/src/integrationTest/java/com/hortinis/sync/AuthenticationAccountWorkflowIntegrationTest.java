package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationChallengePurpose;
import com.hortinis.sync.authentication.AuthenticationCredentialService;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifecycle;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationLoginService;
import com.hortinis.sync.authentication.AuthenticationObservationService;
import com.hortinis.sync.authentication.AuthenticationOperatorRecoveryService;
import com.hortinis.sync.authentication.AuthenticationOperatorTickets;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationPasskeys;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationPublication;
import com.hortinis.sync.authentication.AuthenticationRecoveryService;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionService;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.AuthenticationSetupService;
import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import com.hortinis.sync.authentication.ControlledSessionIdResolver;
import com.hortinis.sync.authentication.CryptoWorkLimiter;
import com.hortinis.sync.authentication.OperatorTicketFiles;
import com.hortinis.sync.authentication.PasswordBlocklist;
import com.hortinis.sync.authentication.PasswordPolicy;
import com.hortinis.sync.authentication.PasswordVerifier;
import com.hortinis.sync.authentication.http.AuthenticationBrowserGuard;
import com.hortinis.sync.authentication.http.AuthenticationErrorHandler;
import com.hortinis.sync.authentication.http.AuthenticationHttpErrors;
import com.hortinis.sync.authentication.http.AuthenticationLifecycleFilter;
import com.hortinis.sync.authentication.http.AuthenticationMutationController;
import com.hortinis.sync.authentication.http.AuthenticationProofBoundary;
import com.hortinis.sync.authentication.http.AuthenticationReadController;
import com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler;
import com.hortinis.sync.authentication.http.RandomSessionCsrfTokenRepository;
import com.hortinis.sync.authentication.http.RequestAuthenticationSession;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountSecurityPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorRecoveryPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionManagementPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionRemovalPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSetupPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.protocol.InvalidRequestException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real password, signed WebAuthn, native servlet transport and fresh PostgreSQL service workflows.
 */
@Testcontainers
class AuthenticationAccountWorkflowIntegrationTest {
  private static final String FORGOTTEN_OPTIONS = "/api/v1/auth/recovery/password/options";
  private static final String FORGOTTEN_PROOF = "/api/v1/auth/recovery/password/proof";
  private static final String RESET_PASSWORD = "/api/v1/auth/recovery/password";
  private static final String LOST_PASSKEY = "/api/v1/auth/recovery/passkey";
  private static final String REPLACEMENT_OPTIONS = "/api/v1/auth/recovery/passkey/options";
  private static final String REPLACEMENT_PROOF = "/api/v1/auth/recovery/passkey/proof";
  private static final String EXPIRED_CASE = "expired";
  private static final String WRONG_PASSWORD_CASE = "wrong-password";
  private static final String MISSING_CASE = "missing";
  private static final String USED_CASE = "used";
  private static final String EPOCH_CASE = "epoch";
  private static final String OPERATOR_OPTIONS = "/api/v1/auth/recovery/operator";
  private static final String OPERATOR_PROOF = "/api/v1/auth/recovery/operator/proof";
  private static final String WEAK_PASSWORD_CASE = "weak-password";
  private static final String REPLACED_CASE = "replaced";
  private static final String TICKET_COUNT = "SELECT count(*) FROM authentication_operator_ticket";
  private static final String CLAIMED_CASE = "claimed";
  private static final String UNKNOWN_CASE = "unknown";
  private static final String PASSWORD_FIELD = "password";
  private static final String STATUS_PATH = "/api/v1/auth/session";
  private static final String CSRF_PATH = "/api/v1/auth/csrf";
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String JSON_MEDIA = "application/json";
  private static final String RECOVERY_CODES_PATH = "/api/v1/auth/recovery-codes";
  private static final String SESSIONS_PATH = "/api/v1/auth/sessions";
  private static final String LOGIN = "synthetic-gardener";
  private static final String NEW_PASSWORD = "copper orchard echoes seventy nine";
  private static final String CHANGE_PASSWORD_PATH = "/api/v1/auth/password";
  private static final String PASSWORD = "violet river lantern forty eight";
  private static final String NATIVE_COUNT = "SELECT count(*) FROM spring_session";
  private static final String DISABLED_CASE = "disabled";
  private static final String OLD_FACTORS_CASE = "old-factors";
  private static final String CREDENTIAL_OPTIONS_PATH = "/api/v1/auth/credentials/options";
  private static final String CREDENTIAL_PATH = "/api/v1/auth/credentials";
  private static final String LOGOUT_PATH = "/api/v1/auth/logout";
  private static final String COOKIE_NAME = "__Host-hortinis-session";
  private static final String SETUP_PATH = "/api/v1/auth/setup";
  private static final String SETUP_PROOF_PATH = "/api/v1/auth/setup/passkey";
  private static final String PASSWORD_PATH = "/api/v1/auth/login/password";
  private static final String PROOF_PATH = "/api/v1/auth/login/passkey";
  private static final String ACCOUNT_COUNT = "SELECT count(*) FROM authentication_account";
  private static final String CREDENTIAL_COUNT = "SELECT count(*) FROM authentication_credential";
  private static final String CODE_COUNT = "SELECT count(*) FROM authentication_recovery_code";
  private static final String FULL_COUNT =
      "SELECT count(*) FROM authentication_session_authority WHERE state = 'full'";
  private static final String AUTHORITY_COUNT =
      "SELECT count(*) FROM authentication_session_authority";
  private static final String CHALLENGE_COUNT = "SELECT count(*) FROM authentication_challenge";
  private static final String COUNTER = "SELECT signature_count FROM authentication_credential";
  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @TempDir java.nio.file.Path httpTicketDirectory;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactions;
  private MutableClock clock;
  private AuthenticationAccountPersistence accounts;
  private AuthenticationSessionPublicationPersistence sessions;
  private AuthenticationChallengePersistence challenges;
  private AuthenticationOperatorTicketPersistence tickets;
  private CurrentAuthenticationAuthorityPersistence authority;
  private AuthenticationPublication publication;
  private AuthenticationPasskeys passkeys;
  private AuthenticationSetupService setup;
  private AuthenticationLoginService login;
  private AuthenticationCredentialService credentials;
  private AuthenticationSessionService sessionControl;
  private AuthenticationRecoveryService recovery;
  private AuthenticationOperatorRecoveryService operatorRecovery;
  private AuthenticationRecoveryCodePersistence.IssuedCodes issuedCodes;

  @BeforeEach
  void freshSchema() {
    String schema = "authentication_workflow_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    transactions = new DataSourceTransactionManager(source);
    clock = new MutableClock();
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    var client = JdbcClient.create(jdbc);
    accounts = new AuthenticationAccountPersistence(client, clock);
    sessions = new AuthenticationSessionPublicationPersistence(client, clock);
    challenges = new AuthenticationChallengePersistence(client, clock, transactions);
    tickets = new AuthenticationOperatorTicketPersistence(client, clock);
    authority = new CurrentAuthenticationAuthorityPersistence(client, clock);
    var codes = new AuthenticationRecoveryCodePersistence(client, clock, authority);
    var eventStore = new AuthenticationSecurityEventPersistence(client, clock);
    publication = new AuthenticationPublication(transactions, eventStore, clock);
    var work = new CryptoWorkLimiter();
    var passwords = new PasswordVerifier(work);
    passkeys = new AuthenticationPasskeys(SyntheticAuthenticationPasskey.ORIGIN, work);
    setup =
        new AuthenticationSetupService(
            publication,
            new AuthenticationSetupPersistence(client, clock, sessions, accounts, codes),
            sessions,
            challenges,
            new PasswordPolicy(PasswordBlocklist.load()),
            passwords,
            passkeys);
    login =
        new AuthenticationLoginService(
            publication, accounts, sessions, challenges, passwords, passkeys);
    var removal = new AuthenticationSessionRemovalPersistence(client);
    credentials =
        new AuthenticationCredentialService(
            publication,
            accounts,
            sessions,
            challenges,
            new AuthenticationAccountSecurityPersistence(client, accounts, removal),
            codes,
            authority,
            new PasswordPolicy(PasswordBlocklist.load()),
            passwords,
            passkeys);
    recovery =
        new AuthenticationRecoveryService(
            publication,
            accounts,
            sessions,
            challenges,
            codes,
            new AuthenticationAccountSecurityPersistence(client, accounts, removal),
            new PasswordPolicy(PasswordBlocklist.load()),
            passwords,
            passkeys);
    operatorRecovery =
        new AuthenticationOperatorRecoveryService(
            publication,
            new AuthenticationOperatorRecoveryPersistence(
                client,
                clock,
                sessions,
                accounts,
                new AuthenticationAccountSecurityPersistence(client, accounts, removal)),
            accounts,
            sessions,
            challenges,
            new PasswordPolicy(PasswordBlocklist.load()),
            passwords,
            passkeys);
    sessionControl =
        new AuthenticationSessionService(
            publication,
            sessions,
            new AuthenticationSessionManagementPersistence(client, clock, sessions, removal),
            removal);
  }

  @Test
  void controlledSetupThenBothFactorLoginCreatesCurrentAuthorityWithFreshCookieAndReference()
      throws Exception {
    final var key = setupAccount(false);
    assertThat(count(ACCOUNT_COUNT)).isEqualTo(1);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(count(TICKET_COUNT)).isZero();
    var ready = prepare(key, 1);
    assertThat(
            authority.findFull(
                ready.observation().verifier(), ready.observation().reference().value()))
        .isEmpty();
    var complete = finish(ready, false);
    assertThat(complete.cookie().getValue()).isNotEqualTo(ready.cookie().getValue());
    assertThat(complete.result().reference()).isNotEqualTo(ready.observation().reference());
    assertThat(
            authority.findFull(complete.result().verifier(), complete.result().reference().value()))
        .isPresent();
    assertThat(
            authority.findFull(
                ready.observation().verifier(), ready.observation().reference().value()))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT password_verifier FROM authentication_account", String.class))
        .startsWith("{argon2id}$argon2id$v=19$m=65536,t=3,p=1$")
        .doesNotContain(PASSWORD);
    assertThat(
            count("SELECT count(*) FROM authentication_security_event WHERE outcome = 'succeeded'"))
        .isEqualTo(2);
  }

  @Test
  void ticketIsClaimedExactlyOnceAndWrongSecretDoesNotStageAnAccount() throws Exception {
    final String secret = ticket();
    Cookie anonymous = bootstrap();
    request(
        SETUP_PATH,
        anonymous,
        transport -> {
          assertThatThrownBy(
                  () ->
                      setup.begin(transport, AuthenticationSecrets.randomValue(), LOGIN, PASSWORD))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count("SELECT count(*) FROM authentication_session_authority WHERE state = 'setup'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM authentication_operator_ticket WHERE "
                    + "claimed_session_reference IS NULL"))
        .isEqualTo(1);
    var staged =
        request(
            SETUP_PATH, anonymous, transport -> setup.begin(transport, secret, LOGIN, PASSWORD));
    request(
        SETUP_PATH,
        bootstrap(),
        transport -> {
          assertThatThrownBy(() -> setup.begin(transport, secret, LOGIN, PASSWORD))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(staged.result()).isNotNull();
    assertThat(count(ACCOUNT_COUNT)).isZero();
  }

  @Test
  void sameTicketOwnerCanReplaceInterruptedSetupOptionsWithoutExtendingItsAbsoluteDeadline()
      throws Exception {
    String secret = ticket();
    var staged =
        request(
            SETUP_PATH, bootstrap(), transport -> setup.begin(transport, secret, LOGIN, PASSWORD));
    UUID predecessor = reference(staged.cookie());
    final Instant originalDeadline = sessions.observe(observation(staged.cookie())).expiresAt();
    clock.advance(java.time.Duration.ofMinutes(4));
    var resumed =
        request(
            SETUP_PATH,
            staged.cookie(),
            transport -> setup.begin(transport, secret, LOGIN, PASSWORD));
    assertThat(reference(resumed.cookie())).isNotEqualTo(predecessor);
    assertThat(resumed.result().getChallenge()).isNotEqualTo(staged.result().getChallenge());
    assertThat(resumed.result().getUser().getId()).isEqualTo(staged.result().getUser().getId());
    assertThat(sessions.observe(observation(resumed.cookie())).expiresAt())
        .isEqualTo(originalDeadline);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_session_authority WHERE "
                    + "session_reference = ?",
                Integer.class,
                predecessor))
        .isZero();
    var receipt =
        challenges.consume(reference(resumed.cookie()), AuthenticationChallengePurpose.SETUP);
    assertThat(receipt.expiresAt()).isEqualTo(originalDeadline);
    var key = new SyntheticAuthenticationPasskey(false);
    var proof = key.registration(resumed.result(), true);
    var complete =
        request(
            SETUP_PROOF_PATH,
            resumed.cookie(),
            transport -> setup.complete(transport, receipt, proof));
    assertThat(complete.result().codes()).hasSize(10);
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(count(ACCOUNT_COUNT)).isEqualTo(1);
  }

  @Test
  void failedUserVerificationSpendsNonceAndPersistsDeniedEventWithoutAccountOrCodes()
      throws Exception {
    var key = new SyntheticAuthenticationPasskey(false);
    var staged = beginSetup();
    var receipt =
        challenges.consume(reference(staged.cookie()), AuthenticationChallengePurpose.SETUP);
    var invalid = key.registration(staged.result(), false);
    request(
        SETUP_PROOF_PATH,
        staged.cookie(),
        transport -> {
          assertThatThrownBy(() -> setup.complete(transport, receipt, invalid))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(ACCOUNT_COUNT)).isZero();
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(count("SELECT count(*) FROM authentication_security_event WHERE outcome = 'denied'"))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                challenges.consume(
                    receipt.sessionReference(), AuthenticationChallengePurpose.SETUP))
        .isInstanceOf(AuthenticationFailedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ticket-replaced", "scope-populated", "mode-changed", EXPIRED_CASE})
  void setupPublicationRechecksTicketEmptyScopeModeAndDeadline(String failure) throws Exception {
    var key = new SyntheticAuthenticationPasskey(false);
    var staged = beginSetup();
    var receipt =
        challenges.consume(reference(staged.cookie()), AuthenticationChallengePurpose.SETUP);
    var proof = key.registration(staged.result(), true);
    switch (failure) {
      case "ticket-replaced" -> ticket();
      case "scope-populated" ->
          jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 1");
      case "mode-changed" ->
          jdbc.update(
              "UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
      default -> clock.advance(AuthenticationLifetime.LIMITED_ABSOLUTE);
    }
    request(
        SETUP_PROOF_PATH,
        staged.cookie(),
        transport -> {
          assertThatThrownBy(() -> setup.complete(transport, receipt, proof))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(ACCOUNT_COUNT)).isZero();
    assertThat(count(CREDENTIAL_COUNT)).isZero();
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(count(CHALLENGE_COUNT)).isZero();
  }

  @Test
  void activationCommitFailureRollsBackAccountKeyCodesAndEventButCannotRestoreSpentNonce()
      throws Exception {
    var key = new SyntheticAuthenticationPasskey(false);
    var staged = beginSetup();
    var receipt =
        challenges.consume(reference(staged.cookie()), AuthenticationChallengePurpose.SETUP);
    var proof = key.registration(staged.result(), true);
    jdbc.execute(
        "CREATE FUNCTION reject_activation() RETURNS TRIGGER LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'Synthetic activation failure'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_activation AFTER INSERT ON authentication_account "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_activation()");
    var failed =
        request(
            SETUP_PROOF_PATH,
            staged.cookie(),
            transport -> {
              assertThatThrownBy(() -> setup.complete(transport, receipt, proof))
                  .isInstanceOf(RuntimeException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(ACCOUNT_COUNT)).isZero();
    assertThat(count(CREDENTIAL_COUNT)).isZero();
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count("SELECT count(*) FROM authentication_security_event")).isZero();
    assertThat(count("SELECT count(*) FROM authentication_session_authority WHERE state = 'setup'"))
        .isEqualTo(1);
  }

  @Test
  void wrongPasswordAndUnknownAccountRetainAnonymousSessionAndSameGenericFailure()
      throws Exception {
    setupAccount(false);
    Cookie anonymous = bootstrap();
    for (String identifier : List.of(LOGIN, "missing-gardener")) {
      var failed =
          request(
              PASSWORD_PATH,
              anonymous,
              transport -> {
                assertThatThrownBy(
                        () -> login.password(transport, identifier, "wrong synthetic password"))
                    .isExactlyInstanceOf(AuthenticationFailedException.class);
                return true;
              });
      assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM authentication_session_authority WHERE state = 'anonymous'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM authentication_security_event WHERE event = "
                    + "'login' AND outcome = 'denied'"))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        EPOCH_CASE,
        DISABLED_CASE,
        PASSWORD_FIELD,
        "credential",
        EXPIRED_CASE,
        "wrong-purpose"
      })
  void assertionCannotPublishAgainstRevokedOrChangedCurrentState(String failure) throws Exception {
    var ready = prepare(setupAccount(false), 1);
    switch (failure) {
      case EPOCH_CASE ->
          jdbc.update("UPDATE authentication_account SET authority_epoch = authority_epoch + 1");
      case DISABLED_CASE -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case PASSWORD_FIELD ->
          jdbc.update(
              "UPDATE authentication_account SET authority_epoch = authority_epoch + 1, "
                  + "password_verifier = "
                  + "'{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic-not-a-credential'");
      case "credential" -> jdbc.update("DELETE FROM authentication_credential");
      case EXPIRED_CASE -> clock.advance(AuthenticationLifetime.LIMITED_ABSOLUTE);
      default -> {}
    }
    var failed =
        request(
            PROOF_PATH,
            ready.cookie(),
            transport -> {
              assertThatThrownBy(
                      () ->
                          login.passkey(
                              transport,
                              ready.receipt(),
                              ready.proof(),
                              failure.equals("wrong-purpose")))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(count(CHALLENGE_COUNT)).isZero();
  }

  @Test
  void concurrentVerifiedOldCountersAreRecheckedInsidePublicationLocks() throws Exception {
    final var key = setupAccount(false);
    var first = prepare(key, 1);
    var second = prepare(key, 1);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var bound = passkeys.account(account, accounts.credentials(account.account()));
    final var firstProof =
        bound.verifyAssertion(passkeys.assertion(bound, first.receipt(), first.proof()));
    var secondProof =
        bound.verifyAssertion(passkeys.assertion(bound, second.receipt(), second.proof()));
    publishVerified(first, account, firstProof, false);
    var denied = publishVerified(second, account, secondProof, true);
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(jdbc.queryForObject(COUNTER, Long.class)).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(count(CHALLENGE_COUNT)).isZero();
  }

  @Test
  void concurrentSixthFullLoginDeniesWithoutEvictingFiveCurrentSessions() throws Exception {
    var key = setupAccount(true);
    for (int index = 0; index < 4; index++) {
      finish(prepare(key, 1), false);
    }
    var fifth = prepare(key, 1);
    var sixth = prepare(key, 1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = executor.submit(() -> finishAtCapacity(fifth));
      var right = executor.submit(() -> finishAtCapacity(sixth));
      assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(count(FULL_COUNT)).isEqualTo(5);
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(
            count("SELECT count(*) FROM authentication_security_event WHERE reason = 'capacity'"))
        .isEqualTo(1);
  }

  @Test
  void stepUpRotatesBothFactorsAndPreservesOriginalAbsoluteDeadline() throws Exception {
    final var key = setupAccount(false);
    var loggedIn = finish(prepare(key, 1), false);
    var original =
        authority
            .findFull(loggedIn.result().verifier(), loggedIn.result().reference().value())
            .orElseThrow();
    clock.advance(java.time.Duration.ofMinutes(6));
    var partial =
        request(
            "/api/v1/auth/step-up/password",
            loggedIn.cookie(),
            transport -> login.stepUpPassword(transport, PASSWORD));
    var receipt =
        challenges.consume(reference(partial.cookie()), AuthenticationChallengePurpose.STEP_UP);
    var signed =
        key.assertion(partial.result(), accounts.findLogin(LOGIN).orElseThrow().userHandle(), 2);
    var completed =
        request(
            "/api/v1/auth/step-up/passkey",
            partial.cookie(),
            transport -> login.passkey(transport, receipt, signed, true));
    var current =
        authority
            .findFull(completed.result().verifier(), completed.result().reference().value())
            .orElseThrow();
    assertThat(current.loginAt()).isEqualTo(original.loginAt());
    assertThat(current.expiresAt()).isEqualTo(original.expiresAt());
    assertThat(current.bothFactorsAt()).isEqualTo(clock.instant());
    assertThat(current.sessionReference()).isNotEqualTo(original.sessionReference());
    assertThat(authority.findFull(loggedIn.result().verifier(), original.sessionReference()))
        .isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(1);
  }

  @Test
  void stepUpCannotAllocateTheThousandAndFirstPartialCeremony() throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    jdbc.update(
        "INSERT INTO authentication_session_authority "
            + "(session_verifier, logout_target, state, created_at, expires_at) "
            + "SELECT encode(sha256(convert_to(n::text, 'UTF8')), 'hex'), "
            + "rtrim(translate(encode(sha256(convert_to(n::text, 'UTF8')), "
            + "'base64'), '+/', '-_'), '='), "
            + "'anonymous', ?, ? FROM generate_series(1, 1000) n",
        java.sql.Timestamp.from(clock.instant()),
        java.sql.Timestamp.from(clock.instant().plus(AuthenticationLifetime.LIMITED_ABSOLUTE)));
    var failed =
        request(
            "/api/v1/auth/step-up/password",
            loggedIn.cookie(),
            transport -> {
              assertThatThrownBy(() -> login.stepUpPassword(transport, PASSWORD))
                  .isInstanceOf(AuthenticationOverloadedException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(
            authority.findFull(loggedIn.result().verifier(), loggedIn.result().reference().value()))
        .isPresent();
  }

  @Test
  void stepUpPartialAndNonceCannotOutliveTheOriginalTwelveHourDeadline() throws Exception {
    final var key = setupAccount(false);
    var loggedIn = finish(prepare(key, 1), false);
    Instant originalLogin =
        clock.instant().minus(AuthenticationLifetime.FULL_ABSOLUTE).plusSeconds(60);
    jdbc.update(
        "UPDATE authentication_session_authority SET full_login_at = ?, "
            + "expires_at = ? WHERE state = 'full'",
        java.sql.Timestamp.from(originalLogin),
        java.sql.Timestamp.from(clock.instant().plusSeconds(60)));
    var partial =
        request(
            "/api/v1/auth/step-up/password",
            loggedIn.cookie(),
            transport -> login.stepUpPassword(transport, PASSWORD));
    var receipt =
        challenges.consume(reference(partial.cookie()), AuthenticationChallengePurpose.STEP_UP);
    assertThat(receipt.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
    var signed =
        key.assertion(partial.result(), accounts.findLogin(LOGIN).orElseThrow().userHandle(), 2);
    clock.advance(java.time.Duration.ofSeconds(60));
    var denied =
        request(
            "/api/v1/auth/step-up/passkey",
            partial.cookie(),
            transport -> {
              assertThatThrownBy(() -> login.passkey(transport, receipt, signed, true))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void publicationRechecksExpiryAfterItsEarlierParentLock() throws Exception {
    var ready = prepare(setupAccount(false), 1);
    var denied =
        request(
            PROOF_PATH,
            ready.cookie(),
            transport -> {
              assertThatThrownBy(
                      () ->
                          publication.mutate(
                              () -> {
                                var parent = sessions.lock(ready.observation());
                                clock.advance(AuthenticationLifetime.LIMITED_ABSOLUTE);
                                return sessions.full(parent, transport);
                              }))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void passwordChangeInvalidatesEveryOldAuthorityAndPreservesOwnershipAndSynchronizationHistory()
      throws Exception {
    final var key = setupAccount(false);
    final var first = finish(prepare(key, 1), false);
    finish(prepare(key, 2), false);
    prepare(key, 3);
    final var original = accounts.findLogin(LOGIN).orElseThrow();
    String newPassword = NEW_PASSWORD;
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                tickets.issueRecovery(
                    original.account(),
                    AuthenticationSecrets.digest(AuthenticationSecrets.randomValue())));
    jdbc.update(
        "INSERT INTO technical_record VALUES (?, ?, 1, 'synthetic " + "retained history')",
        original.scope(),
        UUID.randomUUID());
    var changed =
        request(
            CHANGE_PASSWORD_PATH,
            first.cookie(),
            transport -> credentials.password(transport, PASSWORD, newPassword));
    assertThat(changed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(count(TICKET_COUNT)).isZero();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count("SELECT count(*) FROM technical_record")).isEqualTo(1);
    var current = accounts.findLogin(LOGIN).orElseThrow();
    assertThat(current.account()).isEqualTo(original.account());
    assertThat(current.scope()).isEqualTo(original.scope());
    assertThat(current.epoch()).isEqualTo(original.epoch() + 1);
    assertThat(authority.findFull(first.result().verifier(), first.result().reference().value()))
        .isEmpty();
    var loggedIn = finish(prepare(key, 3, newPassword), false);
    assertThat(
            authority.findFull(loggedIn.result().verifier(), loggedIn.result().reference().value()))
        .isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {WRONG_PASSWORD_CASE, WEAK_PASSWORD_CASE, OLD_FACTORS_CASE})
  void passwordChangeRejectsWrongProofPolicyOrMissingRecentFactors(String failure)
      throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    if (OLD_FACTORS_CASE.equals(failure)) {
      clock.advance(java.time.Duration.ofMinutes(5));
    }
    var denied =
        request(
            CHANGE_PASSWORD_PATH,
            loggedIn.cookie(),
            transport -> {
              String current =
                  WRONG_PASSWORD_CASE.equals(failure) ? "wrong synthetic password" : PASSWORD;
              String replacement = WEAK_PASSWORD_CASE.equals(failure) ? "short" : NEW_PASSWORD;
              Class<? extends RuntimeException> expected =
                  OLD_FACTORS_CASE.equals(failure)
                      ? AuthenticationPreconditionException.class
                      : AuthenticationFailedException.class;
              assertThatThrownBy(() -> credentials.password(transport, current, replacement))
                  .isInstanceOf(expected);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isEqualTo(1);
  }

  @Test
  void verifiedAdditionalKeyAndOwnedRevocationInvalidateOldSessionsAndNeverRemoveLastKey()
      throws Exception {
    var originalKey = setupAccount(false);
    var loggedIn = finish(prepare(originalKey, 1), false);
    var replacement = new SyntheticAuthenticationPasskey(false);
    var options = request(CREDENTIAL_OPTIONS_PATH, loggedIn.cookie(), credentials::options);
    assertThat(options.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    var receipt =
        challenges.consume(
            loggedIn.result().reference().value(), AuthenticationChallengePurpose.CREDENTIAL);
    var proof = replacement.registration(options.result(), true);
    var registered =
        request(
            CREDENTIAL_PATH,
            loggedIn.cookie(),
            transport -> credentials.register(transport, receipt, proof));
    assertThat(registered.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(2);
    assertThat(count(AUTHORITY_COUNT)).isZero();
    var newLogin = finish(prepare(replacement, 1), false);
    var listing = request(CREDENTIAL_PATH, newLogin.cookie(), credentials::list);
    assertThat(listing.result()).hasSize(2);
    byte[] returned = listing.result().getFirst().id();
    returned[0] ^= 1;
    assertThat(listing.result().getFirst().id()).isNotEqualTo(returned);
    var revoked =
        request(
            "/api/v1/auth/credentials/revoke",
            newLogin.cookie(),
            transport -> credentials.revoke(transport, originalKey.id()));
    assertThat(revoked.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(3);
    var lastLogin = finish(prepare(replacement, 2), false);
    var denied =
        request(
            "/api/v1/auth/credentials/revoke",
            lastLogin.cookie(),
            transport -> {
              assertThatThrownBy(() -> credentials.revoke(transport, replacement.id()))
                  .isInstanceOf(AuthenticationPreconditionException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isEqualTo(1);
  }

  @Test
  void concurrentRegistrationAtSevenKeysCannotPublishAnotherNinthOrLeaveOldFullAuthority()
      throws Exception {
    var key = setupAccount(true);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var material = accounts.credentials(account.account()).getFirst();
    for (int index = 0; index < 6; index++) {
      jdbc.update(
          "INSERT INTO authentication_credential VALUES (?, ?, ?, ?, 0, "
              + "true, false, 'Synthetic extra')",
          java.util.Base64.getUrlDecoder().decode(AuthenticationSecrets.randomValue()),
          account.account(),
          material.publicKey(),
          material.algorithm());
    }
    var left = finish(prepare(key, 1), false);
    var right = finish(prepare(key, 1), false);
    var leftOptions = request(CREDENTIAL_OPTIONS_PATH, left.cookie(), credentials::options);
    var rightOptions = request(CREDENTIAL_OPTIONS_PATH, right.cookie(), credentials::options);
    var leftReceipt =
        challenges.consume(
            left.result().reference().value(), AuthenticationChallengePurpose.CREDENTIAL);
    var rightReceipt =
        challenges.consume(
            right.result().reference().value(), AuthenticationChallengePurpose.CREDENTIAL);
    var leftProof =
        new SyntheticAuthenticationPasskey(false).registration(leftOptions.result(), true);
    var rightProof =
        new SyntheticAuthenticationPasskey(false).registration(rightOptions.result(), true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  request(
                          CREDENTIAL_PATH,
                          left.cookie(),
                          transport -> registrationAtCapacity(transport, leftReceipt, leftProof))
                      .result());
      var second =
          executor.submit(
              () ->
                  request(
                          CREDENTIAL_PATH,
                          right.cookie(),
                          transport -> registrationAtCapacity(transport, rightReceipt, rightProof))
                      .result());
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(8);
    assertThat(count(FULL_COUNT)).isZero();
    var current = finish(prepare(key, 1), false);
    var denied =
        request(
            CREDENTIAL_OPTIONS_PATH,
            current.cookie(),
            transport -> {
              assertThatThrownBy(() -> credentials.options(transport))
                  .isInstanceOf(AuthenticationPreconditionException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CHALLENGE_COUNT)).isZero();
  }

  @Test
  void factorChangeCommitFailureRestoresOldHashEpochNativeAuthorityAndCodes() throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    final var original = accounts.findLogin(LOGIN).orElseThrow();
    rejectFactorChangeAtCommit();
    var failed =
        request(
            CHANGE_PASSWORD_PATH,
            loggedIn.cookie(),
            transport -> {
              assertThatThrownBy(() -> credentials.password(transport, PASSWORD, NEW_PASSWORD))
                  .isInstanceOf(RuntimeException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().passwordVerifier())
        .isEqualTo(original.passwordVerifier());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(original.epoch());
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(count(NATIVE_COUNT)).isEqualTo(1);
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(
            authority.findFull(loggedIn.result().verifier(), loggedIn.result().reference().value()))
        .isPresent();
  }

  @Test
  void recoveryCodeReplacementInvalidatesOldCodeSetAndAllOldAuthority() throws Exception {
    final var key = setupAccount(false);
    var loggedIn = finish(prepare(key, 1), false);
    final var oldHashes =
        jdbc.queryForList("SELECT secret_verifier FROM authentication_recovery_code", String.class);
    prepare(key, 2);
    var changed = request(RECOVERY_CODES_PATH, loggedIn.cookie(), credentials::recoveryCodes);
    assertThat(changed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(changed.result().codes()).hasSize(10).doesNotHaveDuplicates();
    var currentHashes =
        jdbc.queryForList("SELECT secret_verifier FROM authentication_recovery_code", String.class);
    assertThat(currentHashes).doesNotContainAnyElementsOf(oldHashes);
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(2);
  }

  @Test
  void sessionListingOmitsPartialAuthorityAndDoesNotRefreshQualifyingActivity() throws Exception {
    final var key = setupAccount(false);
    final var first = finish(prepare(key, 1), false);
    finish(prepare(key, 2), false);
    prepare(key, 3);
    clock.advance(java.time.Duration.ofMinutes(6));
    var listed = request(SESSIONS_PATH, first.cookie(), sessionControl::list);
    assertThat(listed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(listed.result()).hasSize(2);
    assertThat(listed.result().stream().filter(item -> item.current()).count()).isEqualTo(1);
    assertThat(listed.result())
        .allSatisfy(
            item -> assertThat(AuthenticationSecrets.isRandomValue(item.target())).isTrue());
    var current =
        authority
            .findFull(first.result().verifier(), first.result().reference().value())
            .orElseThrow();
    assertThat(current.interactionAt()).isEqualTo(NOW);
  }

  @Test
  void explicitActivityRefreshesIdleOnlyAndCannotReviveAnExpiredSession() throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    var original =
        authority
            .findFull(loggedIn.result().verifier(), loggedIn.result().reference().value())
            .orElseThrow();
    clock.advance(java.time.Duration.ofMinutes(29));
    var active = request("/api/v1/auth/activity", loggedIn.cookie(), sessionControl::activity);
    assertThat(active.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    var current =
        authority.findFull(loggedIn.result().verifier(), original.sessionReference()).orElseThrow();
    assertThat(current.expiresAt()).isEqualTo(original.expiresAt());
    assertThat(current.loginAt()).isEqualTo(original.loginAt());
    assertThat(current.bothFactorsAt()).isEqualTo(original.bothFactorsAt());
    assertThat(current.interactionAt()).isEqualTo(clock.instant());
    clock.advance(AuthenticationLifetime.FULL_IDLE);
    var denied =
        request(
            "/api/v1/auth/activity",
            loggedIn.cookie(),
            transport -> {
              assertThatThrownBy(() -> sessionControl.activity(transport))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(authority.findFull(loggedIn.result().verifier(), original.sessionReference()))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"self", "other", UNKNOWN_CASE})
  void targetedRevocationKeepsOtherAuthorityAndNeverDeletesResponseCookie(String selected)
      throws Exception {
    final var key = setupAccount(false);
    final var first = finish(prepare(key, 1), false);
    var other = finish(prepare(key, 2), false);
    String target =
        UNKNOWN_CASE.equals(selected)
            ? AuthenticationSecrets.randomValue()
            : sessions
                .observe("self".equals(selected) ? first.result() : other.result())
                .logoutTarget();
    var revoked =
        request(
            "/api/v1/auth/sessions/revoke",
            first.cookie(),
            transport -> sessionControl.revoke(transport, target));
    assertThat(revoked.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(UNKNOWN_CASE.equals(selected) ? 2 : 1);
    assertThat(
            authority
                .findFull(first.result().verifier(), first.result().reference().value())
                .isPresent())
        .isEqualTo(!"self".equals(selected));
    assertThat(
            authority
                .findFull(other.result().verifier(), other.result().reference().value())
                .isPresent())
        .isEqualTo(!"other".equals(selected));
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
  }

  @Test
  void lateOriginalLogoutTargetPreservesTheNewerFullSessionAndMatchingLogoutIsIdempotent()
      throws Exception {
    final var key = setupAccount(false);
    var original = finish(prepare(key, 1), false);
    var newer = finish(prepare(key, 2), false);
    String oldTarget = sessions.observe(original.result()).logoutTarget();
    String currentTarget = sessions.observe(newer.result()).logoutTarget();
    var denied =
        request(
            LOGOUT_PATH,
            newer.cookie(),
            transport -> {
              assertThatThrownBy(() -> sessionControl.logout(transport, oldTarget))
                  .isInstanceOf(AuthenticationPreconditionException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(2);
    var complete =
        request(
            LOGOUT_PATH,
            newer.cookie(),
            transport -> sessionControl.logout(transport, currentTarget));
    assertThat(complete.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    var repeated =
        request(
            LOGOUT_PATH,
            newer.cookie(),
            transport -> sessionControl.logout(transport, currentTarget));
    assertThat(repeated.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(
            authority.findFull(original.result().verifier(), original.result().reference().value()))
        .isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"idle", "absolute", "revoked-epoch", DISABLED_CASE, "missing-authority"})
  void logoutOfInactiveAuthorityCompletesAndPhysicallyRemovesItsNativeRow(String failure)
      throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    switch (failure) {
      case "idle" -> clock.advance(AuthenticationLifetime.FULL_IDLE);
      case "absolute" -> clock.advance(AuthenticationLifetime.FULL_ABSOLUTE);
      case "revoked-epoch" ->
          jdbc.update(
              "UPDATE authentication_account SET authority_epoch = " + "authority_epoch + 1");
      case DISABLED_CASE -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      default -> jdbc.update("DELETE FROM authentication_session_authority");
    }
    var complete =
        request(
            LOGOUT_PATH,
            loggedIn.cookie(),
            transport -> sessionControl.logout(transport, AuthenticationSecrets.randomValue()));
    assertThat(complete.result()).isTrue();
    assertThat(complete.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
  }

  @Test
  void registrationFinalPublicationRechecksRecentFactorsAfterItsConsumedChallenge()
      throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    var options = request(CREDENTIAL_OPTIONS_PATH, loggedIn.cookie(), credentials::options);
    var receipt =
        challenges.consume(
            loggedIn.result().reference().value(), AuthenticationChallengePurpose.CREDENTIAL);
    var proof = new SyntheticAuthenticationPasskey(false).registration(options.result(), true);
    clock.advance(AuthenticationLifetime.RECENT_AUTHENTICATION);
    var denied =
        request(
            CREDENTIAL_PATH,
            loggedIn.cookie(),
            transport -> {
              assertThatThrownBy(() -> credentials.register(transport, receipt, proof))
                  .isInstanceOf(AuthenticationPreconditionException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
  }

  @Test
  void revocationRequiresRecentNormalFactorsWhileSelfLogoutDoesNot() throws Exception {
    final var key = setupAccount(false);
    var actor = finish(prepare(key, 1), false);
    var other = finish(prepare(key, 2), false);
    String otherTarget = sessions.observe(other.result()).logoutTarget();
    final String actorTarget = sessions.observe(actor.result()).logoutTarget();
    clock.advance(AuthenticationLifetime.RECENT_AUTHENTICATION);
    var denied =
        request(
            "/api/v1/auth/sessions/revoke",
            actor.cookie(),
            transport -> {
              assertThatThrownBy(() -> sessionControl.revoke(transport, otherTarget))
                  .isInstanceOf(AuthenticationPreconditionException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(2);
    var loggedOut =
        request(
            LOGOUT_PATH,
            actor.cookie(),
            transport -> sessionControl.logout(transport, actorTarget));
    assertThat(loggedOut.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    assertThat(authority.findFull(other.result().verifier(), other.result().reference().value()))
        .isPresent();
  }

  @Test
  void factorRevocationWaitsForCurrentProtectedTransactionBeforeInvalidatingItsAuthority()
      throws Exception {
    var loggedIn = finish(prepare(setupAccount(false), 1), false);
    var full =
        authority
            .findFull(loggedIn.result().verifier(), loggedIn.result().reference().value())
            .orElseThrow();
    var protectedLock = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var held =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            authority.requireCurrent(
                                full.account(),
                                full.sessionReference(),
                                full.epoch(),
                                full.instance(),
                                full.scope(),
                                full.mode());
                            protectedLock.countDown();
                            try {
                              if (!release.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Synthetic lock timeout.");
                              }
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException("Synthetic lock interruption.");
                            }
                            return true;
                          }));
      assertThat(protectedLock.await(10, TimeUnit.SECONDS)).isTrue();
      var changing =
          executor.submit(
              () ->
                  request(
                      CHANGE_PASSWORD_PATH,
                      loggedIn.cookie(),
                      transport -> credentials.password(transport, PASSWORD, NEW_PASSWORD)));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
          waiting =
              count(
                      "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = "
                          + "'Lock' AND query LIKE '%authentication_account%FOR UPDATE%' AND "
                          + "pid <> pg_backend_pid()")
                  > 0;
          if (!waiting) {
            Thread.sleep(20);
          }
        }
        assertThat(waiting).isTrue();
        assertThat(changing.isDone()).isFalse();
        assertThat(authority.findFull(loggedIn.result().verifier(), full.sessionReference()))
            .isPresent();
      } finally {
        release.countDown();
      }
      assertThat(held.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(changing.get(10, TimeUnit.SECONDS).response().getHeaders(HttpHeaders.SET_COOKIE))
          .isEmpty();
    } finally {
      release.countDown();
    }
    assertThat(authority.findFull(loggedIn.result().verifier(), full.sessionReference())).isEmpty();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {MISSING_CASE, "disabled", "wrong", EXPIRED_CASE, USED_CASE})
  void forgottenOptionsDoNotDiscloseAccountOrCodeValidityAndInvalidProofCannotConsume(String change)
      throws Exception {
    final var key = setupAccount(false);
    final String code = issuedCodes.codes().getFirst();
    if (DISABLED_CASE.equals(change)) {
      jdbc.update("UPDATE authentication_account SET status = 'disabled'");
    } else if (EXPIRED_CASE.equals(change)) {
      clock.advance(AuthenticationLifetime.RECOVERY_CODE_ABSOLUTE);
    } else if (USED_CASE.equals(change)) {
      jdbc.update(
          "DELETE FROM authentication_recovery_code WHERE secret_verifier = ?",
          AuthenticationSecrets.digest(code));
    }
    int before = count(CODE_COUNT);
    String requestedLogin = MISSING_CASE.equals(change) ? "unknown-synthetic-gardener" : LOGIN;
    var pending =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport ->
                recovery.forgottenPassword(
                    transport,
                    requestedLogin,
                    "wrong".equals(change) ? AuthenticationSecrets.randomValue() : code));
    assertThat(pending.result().getAllowCredentials()).isEmpty();
    assertThat(pending.result().getChallenge().getBytes()).hasSize(32);
    var parent = sessions.observe(observation(pending.cookie()));
    assertThat(parent.state()).isEqualTo("recovery");
    assertThat(parent.recoveryKind()).isEqualTo(PASSWORD_FIELD);
    assertThat(parent.account()).isNull();
    assertThat(parent.bothFactorsAt()).isNull();
    assertThat(count(CODE_COUNT)).isEqualTo(before);
    final byte[] handle =
        jdbc.queryForObject("SELECT user_handle FROM authentication_account", byte[].class);
    var signed = key.assertion(pending.result(), handle, 1);
    var receipt =
        challenges.consume(parent.reference(), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var denied =
        request(
            FORGOTTEN_PROOF,
            pending.cookie(),
            transport -> {
              assertThatThrownBy(() -> recovery.passwordProof(transport, receipt, signed))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(before);
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(COUNTER)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void forgottenPasswordProofConsumesCodeIntoResetOnlyAuthorityAndCompletionRequiresNormalLogin()
      throws Exception {
    final var key = setupAccount(false);
    final String code = issuedCodes.codes().getFirst();
    final var oldFull = finish(prepare(key, 1), false);
    var pending =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport -> recovery.forgottenPassword(transport, LOGIN, code));
    var pendingReference = reference(pending.cookie());
    assertThat(
            authority.findFull(
                AuthenticationSecrets.digest(pending.cookie().getValue()), pendingReference))
        .isEmpty();
    request(
        RESET_PASSWORD,
        pending.cookie(),
        transport -> {
          assertThatThrownBy(() -> recovery.resetPassword(transport, NEW_PASSWORD))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    var receipt =
        challenges.consume(pendingReference, AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var signed = key.assertion(pending.result(), account.userHandle(), 2);
    var ready =
        request(
            FORGOTTEN_PROOF,
            pending.cookie(),
            transport -> recovery.passwordProof(transport, receipt, signed));
    assertThat(ready.result()).isTrue();
    assertThat(ready.cookie().getValue()).isNotEqualTo(pending.cookie().getValue());
    var parent = sessions.observe(observation(ready.cookie()));
    assertThat(parent.reference()).isNotEqualTo(pendingReference);
    assertThat(parent.account()).isEqualTo(account.account());
    assertThat(parent.bothFactorsAt()).isEqualTo(NOW);
    assertThat(parent.state()).isEqualTo("recovery");
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(
            authority.findFull(
                AuthenticationSecrets.digest(ready.cookie().getValue()), parent.reference()))
        .isEmpty();
    var completed =
        request(
            RESET_PASSWORD,
            ready.cookie(),
            transport -> recovery.resetPassword(transport, NEW_PASSWORD));
    assertThat(completed.result()).isTrue();
    assertThat(completed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(2);
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isEmpty();
    var normal = finish(prepare(key, 3, NEW_PASSWORD), false);
    assertThat(authority.findFull(normal.result().verifier(), normal.result().reference().value()))
        .isPresent();
  }

  @Test
  void restrictedResetRetainsOriginalDeadlineAndExpiryDoesNotRestoreConsumedCode()
      throws Exception {
    final var key = setupAccount(false);
    var pending =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport ->
                recovery.forgottenPassword(transport, LOGIN, issuedCodes.codes().getFirst()));
    clock.advance(java.time.Duration.ofMinutes(4));
    var receipt =
        challenges.consume(
            reference(pending.cookie()), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var signed =
        key.assertion(pending.result(), accounts.findLogin(LOGIN).orElseThrow().userHandle(), 1);
    var ready =
        request(
            FORGOTTEN_PROOF,
            pending.cookie(),
            transport -> recovery.passwordProof(transport, receipt, signed));
    var parent = sessions.observe(observation(ready.cookie()));
    assertThat(parent.createdAt()).isEqualTo(NOW);
    assertThat(parent.expiresAt()).isEqualTo(NOW.plus(AuthenticationLifetime.LIMITED_ABSOLUTE));
    clock.advance(java.time.Duration.ofMinutes(1));
    var denied =
        request(
            RESET_PASSWORD,
            ready.cookie(),
            transport -> {
              assertThatThrownBy(() -> recovery.resetPassword(transport, NEW_PASSWORD))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
  }

  @Test
  void concurrentForgottenPasswordProofsHaveOneCodeWinnerWithoutFullAuthority() throws Exception {
    var key = setupAccount(true);
    final String code = issuedCodes.codes().getFirst();
    var first =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport -> recovery.forgottenPassword(transport, LOGIN, code));
    var second =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport -> recovery.forgottenPassword(transport, LOGIN, code));
    final var firstReceipt =
        challenges.consume(
            reference(first.cookie()), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var secondReceipt =
        challenges.consume(
            reference(second.cookie()), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    byte[] handle = accounts.findLogin(LOGIN).orElseThrow().userHandle();
    final var firstProof = key.assertion(first.result(), handle, 1);
    var secondProof = key.assertion(second.result(), handle, 2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var left =
          executor.submit(
              () ->
                  request(
                          FORGOTTEN_PROOF,
                          first.cookie(),
                          transport -> passwordRecoveryWinner(transport, firstReceipt, firstProof))
                      .result());
      var right =
          executor.submit(
              () ->
                  request(
                          FORGOTTEN_PROOF,
                          second.cookie(),
                          transport ->
                              passwordRecoveryWinner(transport, secondReceipt, secondProof))
                      .result());
      assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM authentication_session_authority WHERE "
                    + "both_factors_at IS NOT NULL"))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {WRONG_PASSWORD_CASE, "wrong-code", MISSING_CASE})
  void lostPasskeyCannotAuthorizeWithOnlyOneFactor(String change) throws Exception {
    setupAccount(false);
    final String code = issuedCodes.codes().getFirst();
    var denied =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport -> {
              assertThatThrownBy(
                      () ->
                          recovery.lostPasskey(
                              transport,
                              MISSING_CASE.equals(change) ? "unknown-synthetic-gardener" : LOGIN,
                              WRONG_PASSWORD_CASE.equals(change)
                                  ? "incorrect synthetic password"
                                  : PASSWORD,
                              "wrong-code".equals(change)
                                  ? AuthenticationSecrets.randomValue()
                                  : code))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void lostPasskeyVerifiesReplacementBeforeRemovingLastKeyAndNeverGrantsFullAuthority()
      throws Exception {
    var oldKey = setupAccount(false);
    final var oldFull = finish(prepare(oldKey, 1), false);
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    assertThat(limited.result()).hasSize(1);
    assertThat(limited.result().getFirst().id()).containsExactly(oldKey.id());
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    var observation = observation(limited.cookie());
    assertThat(authority.findFull(observation.verifier(), observation.reference().value()))
        .isEmpty();
    var options =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, oldKey.id()));
    var newKey = new SyntheticAuthenticationPasskey(false);
    var receipt =
        challenges.consume(
            reference(options.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var signed = newKey.registration(options.result(), true);
    var completed =
        request(
            REPLACEMENT_PROOF,
            options.cookie(),
            transport -> recovery.replacePasskey(transport, receipt, signed));
    assertThat(completed.result()).isTrue();
    assertThat(completed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(
            accounts.credentials(accounts.findLogin(LOGIN).orElseThrow().account()).getFirst().id())
        .containsExactly(newKey.id());
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isEmpty();
    var normal = finish(prepare(newKey, 1), false);
    assertThat(authority.findFull(normal.result().verifier(), normal.result().reference().value()))
        .isPresent();
  }

  @Test
  void interruptedLostPasskeyDoesNotRestoreCodeAndSelectionCannotTargetUnownedKey()
      throws Exception {
    final var key = setupAccount(false);
    final String code = issuedCodes.codes().getFirst();
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport -> recovery.lostPasskey(transport, LOGIN, PASSWORD, code));
    request(
        REPLACEMENT_OPTIONS,
        limited.cookie(),
        transport -> {
          assertThatThrownBy(() -> recovery.replacementOptions(transport, new byte[] {1, 2, 3}))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CHALLENGE_COUNT)).isZero();
    request(
        LOST_PASSKEY,
        bootstrap(),
        transport -> {
          assertThatThrownBy(() -> recovery.lostPasskey(transport, LOGIN, PASSWORD, code))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    clock.advance(java.time.Duration.ofMinutes(4));
    var options =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, key.id()));
    var receipt =
        challenges.consume(
            reference(options.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var replacement =
        new SyntheticAuthenticationPasskey(false).registration(options.result(), true);
    clock.advance(java.time.Duration.ofMinutes(1));
    var denied =
        request(
            REPLACEMENT_PROOF,
            options.cookie(),
            transport -> {
              assertThatThrownBy(() -> recovery.replacePasskey(transport, receipt, replacement))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
  }

  @Test
  void failedReplacementProofSpendsNonceButPreservesOldLastKeyAndAlreadyConsumedCode()
      throws Exception {
    final var key = setupAccount(false);
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    var options =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, key.id()));
    var receipt =
        challenges.consume(
            reference(options.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var invalid = new SyntheticAuthenticationPasskey(false).registration(options.result(), false);
    request(
        REPLACEMENT_PROOF,
        options.cookie(),
        transport -> {
          assertThatThrownBy(() -> recovery.replacePasskey(transport, receipt, invalid))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
  }

  @Test
  void lostKeySelectionRotationRejectsEarlierProofAndRetainsOriginalDeadline() throws Exception {
    final var oldKey = setupAccount(false);
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    var first =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, oldKey.id()));
    final var firstReceipt =
        challenges.consume(
            reference(first.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var newKey = new SyntheticAuthenticationPasskey(false);
    final var firstProof = newKey.registration(first.result(), true);
    clock.advance(java.time.Duration.ofMinutes(1));
    var second =
        request(
            REPLACEMENT_OPTIONS,
            first.cookie(),
            transport -> recovery.replacementOptions(transport, oldKey.id()));
    assertThat(second.cookie().getValue()).isNotEqualTo(first.cookie().getValue());
    var secondParent = sessions.observe(observation(second.cookie()));
    assertThat(secondParent.createdAt()).isEqualTo(NOW);
    assertThat(secondParent.expiresAt())
        .isEqualTo(NOW.plus(AuthenticationLifetime.LIMITED_ABSOLUTE));
    assertThat(secondParent.bothFactorsAt()).isEqualTo(NOW);
    request(
        REPLACEMENT_PROOF,
        second.cookie(),
        transport -> {
          assertThatThrownBy(() -> recovery.replacePasskey(transport, firstReceipt, firstProof))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    var secondReceipt =
        challenges.consume(
            secondParent.reference(), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var secondProof = newKey.registration(second.result(), true);
    assertThat(
            request(
                    REPLACEMENT_PROOF,
                    second.cookie(),
                    transport -> recovery.replacePasskey(transport, secondReceipt, secondProof))
                .result())
        .isTrue();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
  }

  @Test
  void verifiedLostKeyReplacementAtEightKeysCommitsEightWithoutDeletingBeforeProof()
      throws Exception {
    final var oldKey = setupAccount(false);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var material = accounts.credentials(account.account()).getFirst();
    for (int index = 0; index < 7; index++) {
      jdbc.update(
          "INSERT INTO authentication_credential VALUES (?, ?, ?, ?, 0, false, false, NULL)",
          java.util.Base64.getUrlDecoder().decode(AuthenticationSecrets.randomValue()),
          account.account(),
          material.publicKey(),
          material.algorithm());
    }
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    assertThat(limited.result()).hasSize(8);
    var options =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, oldKey.id()));
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(8);
    assertThat(options.result().getExcludeCredentials()).hasSize(8);
    var receipt =
        challenges.consume(
            reference(options.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var newKey = new SyntheticAuthenticationPasskey(false);
    var proof = newKey.registration(options.result(), true);
    assertThat(
            request(
                    REPLACEMENT_PROOF,
                    options.cookie(),
                    transport -> recovery.replacePasskey(transport, receipt, proof))
                .result())
        .isTrue();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(8);
    assertThat(
            accounts.credentials(account.account()).stream()
                .map(AuthenticationAccountPersistence.CredentialSnapshot::id))
        .anySatisfy(id -> assertThat(id).containsExactly(newKey.id()))
        .noneSatisfy(id -> assertThat(id).containsExactly(oldKey.id()));
    assertThat(count(CODE_COUNT)).isEqualTo(9);
  }

  @Test
  void resetCommitFailureKeepsEarlierConsumedCodeSpentAndRestoresFactors() throws Exception {
    final var key = setupAccount(false);
    final var account = accounts.findLogin(LOGIN).orElseThrow();
    var pending =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport ->
                recovery.forgottenPassword(transport, LOGIN, issuedCodes.codes().getFirst()));
    var receipt =
        challenges.consume(
            reference(pending.cookie()), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var proof = key.assertion(pending.result(), account.userHandle(), 1);
    var ready =
        request(
            FORGOTTEN_PROOF,
            pending.cookie(),
            transport -> recovery.passwordProof(transport, receipt, proof));
    rejectFactorChangeAtCommit();
    var failed =
        request(
            RESET_PASSWORD,
            ready.cookie(),
            transport -> {
              assertThatThrownBy(() -> recovery.resetPassword(transport, NEW_PASSWORD))
                  .isInstanceOf(RuntimeException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().passwordVerifier())
        .isEqualTo(account.passwordVerifier());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void proofPublicationCommitFailureRestoresCodeAndCounterButNeverRestoresNonce() throws Exception {
    final var key = setupAccount(false);
    var pending =
        request(
            FORGOTTEN_OPTIONS,
            bootstrap(),
            transport ->
                recovery.forgottenPassword(transport, LOGIN, issuedCodes.codes().getFirst()));
    var receipt =
        challenges.consume(
            reference(pending.cookie()), AuthenticationChallengePurpose.RECOVER_PASSWORD);
    var proof =
        key.assertion(pending.result(), accounts.findLogin(LOGIN).orElseThrow().userHandle(), 1);
    jdbc.execute(
        "CREATE FUNCTION reject_reset_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'Synthetic reset failure'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_reset_authority "
            + "AFTER INSERT ON authentication_session_authority "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            + "EXECUTE FUNCTION reject_reset_authority()");
    var failed =
        request(
            FORGOTTEN_PROOF,
            pending.cookie(),
            transport -> {
              assertThatThrownBy(() -> recovery.passwordProof(transport, receipt, proof))
                  .isInstanceOf(RuntimeException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(COUNTER)).isZero();
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM "
                    + "authentication_session_authority WHERE both_factors_at IS NOT NULL"))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {EPOCH_CASE, "wrong-purpose"})
  void currentRecoveryBindingRejectsChangedEpochOrSubstitutedPurpose(String failure)
      throws Exception {
    final var key = setupAccount(false);
    var limited =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    var options =
        request(
            REPLACEMENT_OPTIONS,
            limited.cookie(),
            transport -> recovery.replacementOptions(transport, key.id()));
    var consumed =
        challenges.consume(
            reference(options.cookie()), AuthenticationChallengePurpose.RECOVER_PASSKEY);
    var proof = new SyntheticAuthenticationPasskey(false).registration(options.result(), true);
    boolean changedEpoch = EPOCH_CASE.equals(failure);
    if (changedEpoch) {
      jdbc.update("UPDATE authentication_account SET authority_epoch = authority_epoch + 1");
    }
    var receipt =
        changedEpoch
            ? consumed
            : new AuthenticationChallengePersistence.ConsumedChallenge(
                consumed.sessionReference(),
                consumed.account(),
                consumed.epoch(),
                AuthenticationChallengePurpose.OPERATOR,
                consumed.challenge(),
                consumed.expiresAt(),
                consumed.recoveryCodeVerifier());
    request(
        REPLACEMENT_PROOF,
        options.cookie(),
        transport -> {
          assertThatThrownBy(() -> recovery.replacePasskey(transport, receipt, proof))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(CODE_COUNT)).isEqualTo(9);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
  }

  private void rejectFactorChangeAtCommit() {
    jdbc.execute(
        "CREATE FUNCTION reject_factor_change() RETURNS TRIGGER LANGUAGE "
            + "plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic factor failure'; "
            + "END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_factor_change AFTER UPDATE ON "
            + "authentication_account DEFERRABLE INITIALLY DEFERRED FOR EACH "
            + "ROW EXECUTE FUNCTION reject_factor_change()");
  }

  @Test
  void operatorRecoveryRequiresNewPasswordAndVerifiedKeyThenInvalidatesAllOldProofs()
      throws Exception {
    final var oldKey = setupAccount(false);
    final var account = accounts.findLogin(LOGIN).orElseThrow();
    final var oldFull = finish(prepare(oldKey, 1), false);
    String ticket = operatorTicket();
    var limited =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    var parent = sessions.observe(observation(limited.cookie()));
    assertThat(parent.account()).isEqualTo(account.account());
    assertThat(parent.recoveryKind()).isEqualTo("operator");
    assertThat(parent.bothFactorsAt()).isNull();
    assertThat(parent.stagedHandle()).containsExactly(account.userHandle());
    assertThat(
            authority.findFull(
                AuthenticationSecrets.digest(limited.cookie().getValue()), parent.reference()))
        .isEmpty();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().passwordVerifier())
        .isEqualTo(account.passwordVerifier());
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    var replacement = new SyntheticAuthenticationPasskey(false);
    var receipt = challenges.consume(parent.reference(), AuthenticationChallengePurpose.OPERATOR);
    var proof = replacement.registration(limited.result(), true);
    var completed =
        request(
            OPERATOR_PROOF,
            limited.cookie(),
            transport -> operatorRecovery.complete(transport, receipt, proof));
    assertThat(completed.result()).isTrue();
    assertThat(completed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.credentials(account.account()).getFirst().id())
        .containsExactly(replacement.id());
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(count(TICKET_COUNT)).isZero();
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(2);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().scope()).isEqualTo(account.scope());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().userHandle())
        .containsExactly(account.userHandle());
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isEmpty();
    var normal = finish(prepare(replacement, 1, NEW_PASSWORD), false);
    assertThat(authority.findFull(normal.result().verifier(), normal.result().reference().value()))
        .isPresent();
    var codes = request(RECOVERY_CODES_PATH, normal.cookie(), credentials::recoveryCodes);
    assertThat(codes.result().codes()).hasSize(10);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"wrong-ticket", WEAK_PASSWORD_CASE, CLAIMED_CASE, EXPIRED_CASE, REPLACED_CASE})
  void operatorTicketAloneOrWrongExpiredReplacedClaimCannotAuthorizeFactorChange(String failure)
      throws Exception {
    final var oldFull = finish(prepare(setupAccount(false), 1), false);
    final String ticket = operatorTicket();
    if (CLAIMED_CASE.equals(failure)) {
      request(
          OPERATOR_OPTIONS,
          bootstrap(),
          transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    } else if (EXPIRED_CASE.equals(failure)) {
      clock.advance(AuthenticationLifetime.TICKET_ABSOLUTE);
    } else if (REPLACED_CASE.equals(failure)) {
      operatorTicket();
    }
    var failed =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> {
              assertThatThrownBy(
                      () ->
                          operatorRecovery.begin(
                              transport,
                              "wrong-ticket".equals(failure)
                                  ? AuthenticationSecrets.randomValue()
                                  : ticket,
                              WEAK_PASSWORD_CASE.equals(failure) ? "short" : NEW_PASSWORD))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isPresent();
  }

  @Test
  void sameOperatorTicketOwnerCanResumeWithFreshOptionsWithinOriginalDeadline() throws Exception {
    setupAccount(false);
    final String ticket = operatorTicket();
    var first =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    clock.advance(java.time.Duration.ofMinutes(4));
    var resumed =
        request(
            OPERATOR_OPTIONS,
            first.cookie(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    assertThat(resumed.cookie().getValue()).isNotEqualTo(first.cookie().getValue());
    var parent = sessions.observe(observation(resumed.cookie()));
    assertThat(parent.createdAt()).isEqualTo(NOW);
    assertThat(parent.expiresAt()).isEqualTo(NOW.plus(AuthenticationLifetime.LIMITED_ABSOLUTE));
    assertThat(resumed.result().getChallenge()).isNotEqualTo(first.result().getChallenge());
    var proof = new SyntheticAuthenticationPasskey(false).registration(resumed.result(), true);
    var receipt = challenges.consume(parent.reference(), AuthenticationChallengePurpose.OPERATOR);
    assertThat(
            request(
                    OPERATOR_PROOF,
                    resumed.cookie(),
                    transport -> operatorRecovery.complete(transport, receipt, proof))
                .result())
        .isTrue();
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {REPLACED_CASE, EXPIRED_CASE})
  void operatorPublicationRechecksExactClaimAndDeadlineAfterConsumedRegistration(String failure)
      throws Exception {
    final var oldKey = setupAccount(false);
    final var oldFull = finish(prepare(oldKey, 1), false);
    String ticket = operatorTicket();
    var limited =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    var receipt =
        challenges.consume(reference(limited.cookie()), AuthenticationChallengePurpose.OPERATOR);
    var proof = new SyntheticAuthenticationPasskey(false).registration(limited.result(), true);
    if (EXPIRED_CASE.equals(failure)) {
      clock.advance(AuthenticationLifetime.LIMITED_ABSOLUTE);
    } else {
      operatorTicket();
    }
    var denied =
        request(
            OPERATOR_PROOF,
            limited.cookie(),
            transport -> {
              assertThatThrownBy(() -> operatorRecovery.complete(transport, receipt, proof))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(
            accounts.credentials(accounts.findLogin(LOGIN).orElseThrow().account()).getFirst().id())
        .containsExactly(oldKey.id());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isPresent();
  }

  @Test
  void failedOperatorUserVerificationSpendsNonceAndPreservesAllOldFactorsAndCodes()
      throws Exception {
    final var oldKey = setupAccount(false);
    String ticket = operatorTicket();
    var limited =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    var receipt =
        challenges.consume(reference(limited.cookie()), AuthenticationChallengePurpose.OPERATOR);
    var proof = new SyntheticAuthenticationPasskey(false).registration(limited.result(), false);
    var denied =
        request(
            OPERATOR_PROOF,
            limited.cookie(),
            transport -> {
              assertThatThrownBy(() -> operatorRecovery.complete(transport, receipt, proof))
                  .isInstanceOf(AuthenticationFailedException.class);
              return true;
            });
    assertThat(denied.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(
            accounts.credentials(accounts.findLogin(LOGIN).orElseThrow().account()).getFirst().id())
        .containsExactly(oldKey.id());
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void operatorCommitFailureRestoresOldPasswordKeysCodesTicketAndExistingAuthority()
      throws Exception {
    final var oldKey = setupAccount(false);
    final var account = accounts.findLogin(LOGIN).orElseThrow();
    final var oldFull = finish(prepare(oldKey, 1), false);
    String ticket = operatorTicket();
    var limited =
        request(
            OPERATOR_OPTIONS,
            bootstrap(),
            transport -> operatorRecovery.begin(transport, ticket, NEW_PASSWORD));
    var receipt =
        challenges.consume(reference(limited.cookie()), AuthenticationChallengePurpose.OPERATOR);
    var proof = new SyntheticAuthenticationPasskey(false).registration(limited.result(), true);
    rejectFactorChangeAtCommit();
    var failed =
        request(
            OPERATOR_PROOF,
            limited.cookie(),
            transport -> {
              assertThatThrownBy(() -> operatorRecovery.complete(transport, receipt, proof))
                  .isInstanceOf(RuntimeException.class);
              return true;
            });
    assertThat(failed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CODE_COUNT)).isEqualTo(10);
    assertThat(count(CREDENTIAL_COUNT)).isEqualTo(1);
    assertThat(accounts.credentials(account.account()).getFirst().id())
        .containsExactly(oldKey.id());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().passwordVerifier())
        .isEqualTo(account.passwordVerifier());
    assertThat(accounts.findLogin(LOGIN).orElseThrow().epoch()).isEqualTo(1);
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(TICKET_COUNT)).isEqualTo(1);
    assertThat(
            authority.findFull(oldFull.result().verifier(), oldFull.result().reference().value()))
        .isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", UNKNOWN_CASE, "mismatched", "oversized", "duplicate"})
  void httpProofBoundaryCommitsNonceBeforeEveryMalformedEnvelopeAndAuditsFixedDenial(String input)
      throws Exception {
    setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    String body =
        switch (input) {
          case "malformed" -> "{";
          case UNKNOWN_CASE -> "{\"synthetic-private-proof-canary\":true}";
          case "mismatched" ->
              "{\"id\":\"AA\",\"rawId\":\"AQ\",\"type\":\"public-key\"," + "\"response\":{}}";
          case "oversized" -> " ".repeat(65_537);
          default -> "{\"id\":\"AA\",\"id\":\"AA\"}";
        };
    var boundary = proofBoundary(new AuthenticationAttemptLimiter(clock));
    request(
        PROOF_PATH,
        partial.cookie(),
        transport -> {
          var http = proofRequest(PROOF_PATH, body);
          assertThatThrownBy(() -> boundary.read(http, transport))
              .isInstanceOf(InvalidRequestException.class)
              .hasMessage("The request is invalid.")
              .hasCause(null);
          assertThat(count(CHALLENGE_COUNT)).isZero();
          assertThatThrownBy(() -> boundary.read(http, transport))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM authentication_security_event "
                    + "WHERE event = 'login' AND outcome = 'denied' AND reason = 'invalid-proof'"))
        .isEqualTo(2);
  }

  @Test
  void httpProofBoundaryReturnsOnlyTypedBoundProofAndReplayCannotCreateFullAuthority()
      throws Exception {
    final var key = setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    byte[] handle =
        jdbc.queryForObject("SELECT user_handle FROM authentication_account", byte[].class);
    var proof = key.assertion(partial.result(), handle, 1);
    var response = proof.getResponse();
    String body =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                java.util.Map.of(
                    "id",
                    proof.getId(),
                    "rawId",
                    proof.getRawId().toBase64UrlString(),
                    "type",
                    "public-key",
                    "response",
                    java.util.Map.of(
                        "clientDataJSON",
                        response.getClientDataJSON().toBase64UrlString(),
                        "authenticatorData",
                        response.getAuthenticatorData().toBase64UrlString(),
                        "signature",
                        response.getSignature().toBase64UrlString(),
                        "userHandle",
                        response.getUserHandle().toBase64UrlString())));
    var boundary = proofBoundary(new AuthenticationAttemptLimiter(clock));
    var complete =
        request(
            PROOF_PATH,
            partial.cookie(),
            transport -> {
              var parsed = boundary.read(proofRequest(PROOF_PATH, body), transport);
              assertThat(parsed.registration()).isNull();
              assertThat(parsed.toString()).isEqualTo("AuthenticationProofRequest[redacted]");
              assertThat(count(CHALLENGE_COUNT)).isZero();
              return login.passkey(transport, parsed.receipt(), parsed.assertion(), false);
            });
    assertThat(count(FULL_COUNT)).isEqualTo(1);
    request(
        PROOF_PATH,
        complete.cookie(),
        transport -> {
          assertThatThrownBy(() -> boundary.read(proofRequest(PROOF_PATH, body), transport))
              .isInstanceOf(AuthenticationFailedException.class);
          return true;
        });
    assertThat(count(FULL_COUNT)).isEqualTo(1);
  }

  @Test
  void httpProofBoundaryRejectsWrongRoutePurposeAndThrottlesTrustedPeerBeforeBodyRead()
      throws Exception {
    setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    final var limiter = new AuthenticationAttemptLimiter(clock);
    var boundary = proofBoundary(limiter);
    for (int index = 0; index < 20; index++) {
      assertThat(limiter.admitSource("127.0.0.1")).isTrue();
    }
    request(
        PROOF_PATH,
        partial.cookie(),
        transport -> {
          var http = proofRequest(PROOF_PATH, "{");
          http.addHeader("X-Forwarded-For", "synthetic-other-source");
          assertThatThrownBy(() -> boundary.read(http, transport))
              .isInstanceOf(AuthenticationOverloadedException.class);
          assertThat(count(CHALLENGE_COUNT)).isEqualTo(1);
          return true;
        });
    clock.advance(java.time.Duration.ofMinutes(1));
    request(
        SETUP_PROOF_PATH,
        partial.cookie(),
        transport -> {
          assertThatThrownBy(() -> boundary.read(proofRequest(SETUP_PROOF_PATH, "{"), transport))
              .isInstanceOf(AuthenticationFailedException.class);
          assertThat(count(CHALLENGE_COUNT)).isZero();
          return true;
        });
    assertThat(count(FULL_COUNT)).isZero();
  }

  private AuthenticationProofBoundary proofBoundary(AuthenticationAttemptLimiter limiter) {
    return new AuthenticationProofBoundary(
        challenges,
        limiter,
        publication,
        new AuthenticationSecurityEventPersistence(JdbcClient.create(jdbc), clock));
  }

  private static MockHttpServletRequest proofRequest(String path, String body) {
    var request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr("127.0.0.1");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }

  @Test
  void httpWorkflowPerformsFreshSetupBothFactorLoginMinimizedReadsAndCookieFreeLogout()
      throws Exception {
    var mvc = authenticationHttp();
    assertThat(httpGet(mvc, STATUS_PATH, null).getContentAsString())
        .isEqualTo("{\"state\":\"anonymous\"}");
    assertThat(count(NATIVE_COUNT)).isZero();
    Cookie anonymous = httpCookie(httpGet(mvc, CSRF_PATH, null));
    var staged =
        httpPost(
            mvc,
            SETUP_PATH,
            anonymous,
            java.util.Map.of("ticket", ticket(), "login", LOGIN, PASSWORD_FIELD, PASSWORD));
    assertThat(staged.getStatus()).isEqualTo(200);
    Cookie setupCookie = httpCookie(staged);
    var key = new SyntheticAuthenticationPasskey(false);
    var parent = sessions.observe(observation(setupCookie));
    byte[] nonce =
        jdbc.queryForObject("SELECT challenge FROM authentication_challenge", byte[].class);
    var options = passkeys.setup(parent).creationOptions(nonce);
    assertThat(
            JsonMapper.builder()
                .build()
                .readTree(staged.getContentAsString())
                .get("challenge")
                .stringValue())
        .isEqualTo(options.getChallenge().toBase64UrlString());
    var registered = key.registration(options, true).getCredential();
    var registeredResponse = registered.getResponse();
    var activation =
        httpPost(
            mvc,
            SETUP_PROOF_PATH,
            setupCookie,
            java.util.Map.of(
                "id",
                registered.getId(),
                "rawId",
                registered.getRawId().toBase64UrlString(),
                "type",
                "public-key",
                "response",
                java.util.Map.of(
                    "clientDataJSON", registeredResponse.getClientDataJSON().toBase64UrlString(),
                    "attestationObject",
                        registeredResponse.getAttestationObject().toBase64UrlString())));
    assertThat(activation.getStatus()).isEqualTo(200);
    assertThat(activation.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(
            JsonMapper.builder()
                .build()
                .readTree(activation.getContentAsString())
                .get("codes")
                .size())
        .isEqualTo(10);
    assertThat(count(FULL_COUNT)).isZero();
    anonymous = httpCookie(httpGet(mvc, CSRF_PATH, null));
    var password =
        httpPost(
            mvc,
            PASSWORD_PATH,
            anonymous,
            java.util.Map.of("login", LOGIN, PASSWORD_FIELD, PASSWORD));
    assertThat(password.getStatus()).isEqualTo(200);
    assertThat(password.getContentAsString()).doesNotContain("allowCredentials", "accountId");
    Cookie partialCookie = httpCookie(password);
    assertThat(httpGet(mvc, STATUS_PATH, partialCookie).getContentAsString())
        .isEqualTo("{\"state\":\"password\"}");
    assertThat(httpGet(mvc, SESSIONS_PATH, partialCookie).getStatus()).isEqualTo(401);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var bound = passkeys.account(account, accounts.credentials(account.account()));
    nonce = jdbc.queryForObject("SELECT challenge FROM authentication_challenge", byte[].class);
    var assertion = key.assertion(bound.assertionOptions(nonce), account.userHandle(), 1);
    var response = assertion.getResponse();
    var full =
        httpPost(
            mvc,
            PROOF_PATH,
            partialCookie,
            java.util.Map.of(
                "id",
                assertion.getId(),
                "rawId",
                assertion.getRawId().toBase64UrlString(),
                "type",
                "public-key",
                "response",
                java.util.Map.of(
                    "clientDataJSON", response.getClientDataJSON().toBase64UrlString(),
                    "authenticatorData", response.getAuthenticatorData().toBase64UrlString(),
                    "signature", response.getSignature().toBase64UrlString(),
                    "userHandle", response.getUserHandle().toBase64UrlString())));
    assertThat(full.getStatus()).isEqualTo(200);
    final Cookie fullCookie = httpCookie(full);
    var status = JsonMapper.builder().build().readTree(full.getContentAsString());
    assertThat(status.get("state").stringValue()).isEqualTo("authenticated");
    assertThat(status.get("accountId").stringValue()).isEqualTo(account.account().toString());
    clock.advance(java.time.Duration.ofMinutes(1));
    assertThat(httpGet(mvc, SESSIONS_PATH, fullCookie).getStatus()).isEqualTo(200);
    assertThat(httpGet(mvc, CREDENTIAL_PATH, fullCookie).getContentAsString())
        .contains("credentials")
        .doesNotContain("publicKey", "signatureCount", "transports");
    assertThat(
            jdbc.queryForObject(
                    "SELECT last_interaction_at FROM authentication_session_authority",
                    java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(NOW);
    var logout =
        httpPost(
            mvc,
            LOGOUT_PATH,
            fullCookie,
            java.util.Map.of("target", status.get("logoutTarget").stringValue()));
    assertThat(logout.getStatus()).isEqualTo(200);
    assertThat(logout.getContentAsString()).isEqualTo("{\"completed\":true}");
    assertThat(logout.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(FULL_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    var expiredStatus = httpGet(mvc, STATUS_PATH, fullCookie);
    assertThat(expiredStatus.getStatus()).isEqualTo(200);
    assertThat(expiredStatus.getContentAsString()).isEqualTo("{\"state\":\"anonymous\"}");
    assertThat(expiredStatus.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(NATIVE_COUNT)).isZero();
  }

  @Test
  void httpWorkflowGuardsDenyBeforeProofConsumptionAndAdmittedMalformedProofSpendsNonce()
      throws Exception {
    setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    var mvc = authenticationHttp();
    String token = httpCsrf(mvc, partial.cookie());
    var wrongOrigin =
        mvc.perform(
                browser(post(PROOF_PATH), partial.cookie())
                    .header(CSRF_HEADER, token)
                    .header("Origin", "https://untrusted.invalid")
                    .contentType(JSON_MEDIA)
                    .content("{"))
            .andReturn()
            .getResponse();
    assertThat(wrongOrigin.getStatus()).isEqualTo(403);
    assertThat(count(CHALLENGE_COUNT)).isEqualTo(1);
    var wrongCsrf =
        mvc.perform(
                browser(post(PROOF_PATH), partial.cookie())
                    .header(CSRF_HEADER, AuthenticationSecrets.randomValue())
                    .contentType(JSON_MEDIA)
                    .content("{"))
            .andReturn()
            .getResponse();
    assertThat(wrongCsrf.getStatus()).isEqualTo(403);
    assertThat(count(CHALLENGE_COUNT)).isEqualTo(1);
    var malformed =
        mvc.perform(
                browser(post(PROOF_PATH), partial.cookie())
                    .header(CSRF_HEADER, token)
                    .contentType(JSON_MEDIA)
                    .content("{"))
            .andReturn()
            .getResponse();
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(malformed.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(CHALLENGE_COUNT)).isZero();
    assertThat(count(FULL_COUNT)).isZero();
  }

  @Test
  void httpWorkflowNextAccessPurgesExpiredTransportBeforeStatusWithoutCookieDeletion()
      throws Exception {
    setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    var mvc = authenticationHttp();
    clock.advance(java.time.Duration.ofMinutes(5));
    var status = httpGet(mvc, STATUS_PATH, partial.cookie());
    assertThat(status.getStatus()).isEqualTo(200);
    assertThat(status.getContentAsString()).isEqualTo("{\"state\":\"anonymous\"}");
    assertThat(status.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(NATIVE_COUNT)).isZero();
    assertThat(count(CHALLENGE_COUNT)).isZero();
  }

  private MockMvc authenticationHttp() throws Exception {
    final var limiter = new AuthenticationAttemptLimiter(clock);
    final var observation =
        new AuthenticationObservationService(
            publication,
            new AuthenticationSessionAdmissionPersistence(JdbcClient.create(jdbc), clock),
            sessions,
            accounts);
    var nativeSessions =
        new SessionRepositoryFilter<>(AuthenticationJdbcSessions.create(jdbc, transactions));
    nativeSessions.setHttpSessionIdResolver(new ControlledSessionIdResolver());
    var csrf = new CsrfFilter(new RandomSessionCsrfTokenRepository());
    csrf.setRequestHandler(new HeaderOnlyCsrfTokenRequestHandler());
    csrf.setAccessDeniedHandler(
        (request, response, failure) -> AuthenticationHttpErrors.DENIED.write(response));
    java.nio.file.Files.setPosixFilePermissions(
        httpTicketDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var lifecycle =
        new AuthenticationLifecycle(
            new AuthenticationLifecyclePersistence(JdbcClient.create(jdbc), clock, transactions),
            new AuthenticationOperatorTickets(
                tickets, new OperatorTicketFiles(httpTicketDirectory), transactions),
            limiter);
    lifecycle.afterSingletonsInstantiated();
    return MockMvcBuilders.standaloneSetup(
            new AuthenticationReadController(observation, credentials, sessionControl),
            new AuthenticationMutationController(
                setup,
                login,
                credentials,
                recovery,
                operatorRecovery,
                sessionControl,
                observation,
                proofBoundary(limiter),
                limiter))
        .setControllerAdvice(new AuthenticationErrorHandler())
        .addFilters(
            new AuthenticationBrowserGuard(SyntheticAuthenticationPasskey.ORIGIN, false),
            new AuthenticationLifecycleFilter(lifecycle),
            nativeSessions,
            csrf)
        .build();
  }

  private static MockHttpServletResponse httpGet(MockMvc mvc, String path, Cookie cookie)
      throws Exception {
    return mvc.perform(browser(get(path), cookie)).andReturn().getResponse();
  }

  private static MockHttpServletResponse httpPost(
      MockMvc mvc, String path, Cookie cookie, Object body) throws Exception {
    var response =
        mvc.perform(
                browser(post(path), cookie)
                    .header(CSRF_HEADER, httpCsrf(mvc, cookie))
                    .contentType(JSON_MEDIA)
                    .content(JsonMapper.builder().build().writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    return response;
  }

  private static String httpCsrf(MockMvc mvc, Cookie cookie) throws Exception {
    var response = httpGet(mvc, CSRF_PATH, cookie);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    return JsonMapper.builder()
        .build()
        .readTree(response.getContentAsString())
        .get("token")
        .stringValue();
  }

  private static Cookie httpCookie(MockHttpServletResponse response) {
    return new Exchange<>(null, response).cookie();
  }

  private static MockHttpServletRequestBuilder browser(
      MockHttpServletRequestBuilder request, Cookie cookie) {
    request
        .header("Origin", SyntheticAuthenticationPasskey.ORIGIN)
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Dest", "empty");
    if (cookie != null) {
      request.cookie(cookie);
    }
    return request;
  }

  @Test
  void httpRecoveryAvailabilitySurvivesReloadUsesExactWarningBoundaryAndNeverRenewsInteraction()
      throws Exception {
    var key = setupAccount(false);
    final var full = finish(prepare(key, 1), false);
    var mvc = authenticationHttp();
    var status = httpGet(mvc, RECOVERY_CODES_PATH, full.cookie());
    assertThat(status.getStatus()).isEqualTo(200);
    assertThat(status.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(status.getContentAsString())
        .contains("\"remaining\":10", "\"expiresSoon\":false")
        .doesNotContain(issuedCodes.codes().getFirst(), "secretVerifier", "codes");
    jdbc.update(
        "UPDATE authentication_recovery_code SET created_at = ?, expires_at = ?",
        java.sql.Timestamp.from(NOW.minus(java.time.Duration.ofDays(335)).plusSeconds(1)),
        java.sql.Timestamp.from(NOW.plus(java.time.Duration.ofDays(30)).plusSeconds(1)));
    assertThat(httpGet(mvc, RECOVERY_CODES_PATH, full.cookie()).getContentAsString())
        .contains("\"expiresSoon\":false");
    clock.advance(java.time.Duration.ofSeconds(1));
    assertThat(
            httpGet(authenticationHttp(), RECOVERY_CODES_PATH, full.cookie()).getContentAsString())
        .contains("\"remaining\":10", "\"expiresSoon\":true");
    jdbc.update(
        "DELETE FROM authentication_recovery_code WHERE secret_verifier = ?",
        AuthenticationSecrets.digest(issuedCodes.codes().getFirst()));
    assertThat(httpGet(mvc, RECOVERY_CODES_PATH, full.cookie()).getContentAsString())
        .contains("\"remaining\":9");
    jdbc.update(
        "UPDATE authentication_recovery_code SET created_at = ?, expires_at = ?",
        java.sql.Timestamp.from(NOW.minus(java.time.Duration.ofDays(365)).plusSeconds(1)),
        java.sql.Timestamp.from(NOW.plusSeconds(1)));
    var empty = httpGet(mvc, RECOVERY_CODES_PATH, full.cookie());
    assertThat(empty.getContentAsString())
        .contains("\"remaining\":0", "\"expiresSoon\":false")
        .doesNotContain("expiresAt");
    assertThat(count(CODE_COUNT)).isZero();
    assertThat(
            jdbc.queryForObject(
                    "SELECT last_interaction_at FROM authentication_session_authority",
                    java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(NOW);
  }

  @Test
  void httpRestrictedCredentialReloadShowsOwnedLostKeyChoicesButDeniesFullRecoveryMetadata()
      throws Exception {
    var key = setupAccount(false);
    var partial =
        request(
            PASSWORD_PATH, bootstrap(), transport -> login.password(transport, LOGIN, PASSWORD));
    var mvc = authenticationHttp();
    assertThat(httpGet(mvc, RECOVERY_CODES_PATH, partial.cookie()).getStatus()).isEqualTo(401);
    var lost =
        request(
            LOST_PASSKEY,
            bootstrap(),
            transport ->
                recovery.lostPasskey(transport, LOGIN, PASSWORD, issuedCodes.codes().getFirst()));
    var choices = httpGet(mvc, CREDENTIAL_PATH, lost.cookie());
    assertThat(choices.getStatus()).isEqualTo(200);
    assertThat(choices.getContentAsString())
        .contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.id()))
        .doesNotContain("publicKey");
    assertThat(httpGet(mvc, RECOVERY_CODES_PATH, lost.cookie()).getStatus()).isEqualTo(401);
    assertThat(httpGet(mvc, SESSIONS_PATH, lost.cookie()).getStatus()).isEqualTo(401);
    clock.advance(java.time.Duration.ofMinutes(5));
    assertThat(httpGet(mvc, CREDENTIAL_PATH, lost.cookie()).getStatus()).isEqualTo(401);
  }

  private String operatorTicket() {
    String secret = AuthenticationSecrets.randomValue();
    UUID account = accounts.findLogin(LOGIN).orElseThrow().account();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> tickets.issueRecovery(account, AuthenticationSecrets.digest(secret)));
    return secret;
  }

  private boolean passwordRecoveryWinner(
      RequestAuthenticationSession transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> signed) {
    try {
      return recovery.passwordProof(transport, receipt, signed);
    } catch (AuthenticationFailedException expected) {
      return false;
    }
  }

  private boolean registrationAtCapacity(
      RequestAuthenticationSession transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      org.springframework.security.web.webauthn.management.RelyingPartyPublicKey proof) {
    try {
      return credentials.register(transport, receipt, proof);
    } catch (AuthenticationFailedException | AuthenticationPreconditionException expected) {
      return false;
    }
  }

  private SyntheticAuthenticationPasskey setupAccount(boolean backupEligible) throws Exception {
    var key = new SyntheticAuthenticationPasskey(backupEligible);
    var staged = beginSetup();
    var receipt =
        challenges.consume(reference(staged.cookie()), AuthenticationChallengePurpose.SETUP);
    var signed = key.registration(staged.result(), true);
    var completed =
        request(
            SETUP_PROOF_PATH,
            staged.cookie(),
            transport -> setup.complete(transport, receipt, signed));
    assertThat(completed.response().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    var issued = completed.result();
    issuedCodes = issued;
    assertThat(issued.codes()).hasSize(10).doesNotHaveDuplicates();
    assertThat(issued.expiresAt())
        .isEqualTo(NOW.plus(AuthenticationLifetime.RECOVERY_CODE_ABSOLUTE));
    List<String> hashes =
        jdbc.queryForList("SELECT secret_verifier FROM authentication_recovery_code", String.class);
    assertThat(hashes)
        .containsExactlyInAnyOrderElementsOf(
            issued.codes().stream().map(AuthenticationSecrets::digest).toList());
    assertThat(issued.toString()).doesNotContain(issued.codes().getFirst());
    return key;
  }

  private Exchange<PublicKeyCredentialCreationOptions> beginSetup() throws Exception {
    final String secret = ticket();
    return request(
        SETUP_PATH, bootstrap(), transport -> setup.begin(transport, secret, LOGIN, PASSWORD));
  }

  private String ticket() {
    String secret = AuthenticationSecrets.randomValue();
    new TransactionTemplate(transactions)
        .executeWithoutResult(status -> tickets.issueSetup(AuthenticationSecrets.digest(secret)));
    return secret;
  }

  private Prepared prepare(SyntheticAuthenticationPasskey key, long counter) throws Exception {
    return prepare(key, counter, PASSWORD);
  }

  private Prepared prepare(
      SyntheticAuthenticationPasskey key, long counter, String suppliedPassword) throws Exception {
    var partial =
        request(
            PASSWORD_PATH,
            bootstrap(),
            transport -> login.password(transport, LOGIN, suppliedPassword));
    var receipt =
        challenges.consume(reference(partial.cookie()), AuthenticationChallengePurpose.LOGIN);
    var account = accounts.findLogin(LOGIN).orElseThrow();
    var signed = key.assertion(partial.result(), account.userHandle(), counter);
    var observation = observation(partial.cookie());
    return new Prepared(partial.cookie(), receipt, signed, observation);
  }

  private Exchange<AuthenticationSessionTransport.Observation> finish(
      Prepared prepared, boolean stepUp) throws Exception {
    return request(
        PROOF_PATH,
        prepared.cookie(),
        transport -> login.passkey(transport, prepared.receipt(), prepared.proof(), stepUp));
  }

  private boolean finishAtCapacity(Prepared prepared) throws Exception {
    return request(
            PROOF_PATH,
            prepared.cookie(),
            transport -> {
              try {
                login.passkey(transport, prepared.receipt(), prepared.proof(), false);
                return true;
              } catch (AuthenticationOverloadedException expected) {
                return false;
              }
            })
        .result();
  }

  private Exchange<Boolean> publishVerified(
      Prepared prepared,
      AuthenticationAccountPersistence.AccountSnapshot account,
      BoundWebAuthnOperations.VerifiedCredential proof,
      boolean denied)
      throws Exception {
    return request(
        PROOF_PATH,
        prepared.cookie(),
        transport -> {
          Supplier<Boolean> operation =
              () ->
                  publication.mutate(
                      () -> {
                        var parent = sessions.lock(prepared.observation());
                        var current = accounts.requireUnchanged(account);
                        publication.requireBound(
                            parent,
                            prepared.receipt(),
                            proof,
                            AuthenticationChallengePurpose.LOGIN,
                            BoundWebAuthnOperations.ProofKind.ASSERTION,
                            passkeys.origin());
                        accounts.publishAssertion(current, proof);
                        sessions.full(parent, transport);
                        return true;
                      });
          if (denied) {
            assertThatThrownBy(operation::get).isInstanceOf(AuthenticationFailedException.class);
            return false;
          }
          return operation.get();
        });
  }

  private Cookie bootstrap() throws Exception {
    return request(
            CSRF_PATH,
            null,
            transport ->
                publication.mutate(
                    () ->
                        transport.bootstrap(
                            new AuthenticationSessionAdmissionPersistence(
                                    JdbcClient.create(jdbc), clock)
                                ::admitAnonymous)))
        .cookie();
  }

  private AuthenticationSessionTransport.Observation observation(Cookie cookie) {
    return new AuthenticationSessionTransport.Observation(
        new com.hortinis.sync.authentication.SessionAuthorityReference(reference(cookie)),
        AuthenticationSecrets.digest(cookie.getValue()));
  }

  private UUID reference(Cookie cookie) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT session_reference FROM authentication_session_authority "
                + "WHERE session_verifier = ?",
            UUID.class,
            AuthenticationSecrets.digest(cookie.getValue())));
  }

  private <T> Exchange<T> request(
      String path, Cookie cookie, Function<RequestAuthenticationSession, T> operation)
      throws Exception {
    return request(AuthenticationJdbcSessions.create(jdbc, transactions), path, cookie, operation);
  }

  private <T, S extends Session> Exchange<T> request(
      SessionRepository<S> repository,
      String path,
      Cookie cookie,
      Function<RequestAuthenticationSession, T> operation)
      throws Exception {
    var filter = new SessionRepositoryFilter<>(repository);
    filter.setHttpSessionIdResolver(new ControlledSessionIdResolver());
    var request = new MockHttpServletRequest(path.endsWith("/csrf") ? "GET" : "POST", path);
    if (cookie != null) {
      request.setCookies(cookie);
    }
    var response = new MockHttpServletResponse();
    var value = new AtomicReference<T>();
    filter.doFilter(
        request,
        response,
        (nativeRequest, nativeResponse) ->
            value.set(
                operation.apply(
                    new RequestAuthenticationSession(
                        (HttpServletRequest) nativeRequest,
                        (HttpServletResponse) nativeResponse))));
    return new Exchange<>(value.get(), response);
  }

  private int count(String trustedQuery) {
    return Objects.requireNonNull(jdbc.queryForObject(trustedQuery, Integer.class));
  }

  private record Exchange<T>(T result, MockHttpServletResponse response) {
    Cookie cookie() {
      String header = Objects.requireNonNull(response.getHeader(HttpHeaders.SET_COOKIE));
      return new Cookie(
          COOKIE_NAME, header.substring(COOKIE_NAME.length() + 1, header.indexOf(';')));
    }

    @Override
    public String toString() {
      return "AuthenticationExchange[redacted]";
    }
  }

  private record Prepared(
      Cookie cookie,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> proof,
      AuthenticationSessionTransport.Observation observation) {
    @Override
    public String toString() {
      return "PreparedAuthentication[redacted]";
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant current = NOW;

    void advance(java.time.Duration amount) {
      current = current.plus(amount);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
