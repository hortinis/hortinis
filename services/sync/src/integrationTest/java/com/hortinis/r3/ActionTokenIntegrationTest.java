package com.hortinis.r3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.authentication.ActionMailAdmission;
import com.hortinis.sync.authentication.ActionMailDelivery;
import com.hortinis.sync.authentication.ActionTokenDelivery;
import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence.IssuedToken;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Fresh R3 evidence; fixtures never activate, change a password or publish account authority. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ActionTokenIntegrationTest {
  private static final String EMAIL = "Gardener@Example.test";
  private static final String ACCOUNTS = "SELECT count(*) FROM authentication_account";
  private static final String COUNT = "SELECT count(*) FROM authentication_action_token";
  private static final Instant START = Instant.parse("2026-10-17T12:00:00Z");
  private static final String VERIFIER = "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  private final MutableClock clock = new MutableClock();
  private JdbcTemplate jdbc;
  private DriverManagerDataSource source;
  private DataSourceTransactionManager manager;
  private TransactionTemplate transactions;
  private ActionTokenPersistence tokens;
  private AuthenticationLifecyclePersistence lifecycle;
  private UUID account;

  @BeforeEach
  void freshSchema() {
    String schema = "action_tokens_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    transactions = new TransactionTemplate(manager);
    tokens = new ActionTokenPersistence(JdbcClient.create(source), clock);
    lifecycle = new AuthenticationLifecyclePersistence(JdbcClient.create(source), clock, manager);
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    account = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authentication_account (account_id, server_instance_id, "
            + "synchronization_scope_id, delivery_email, canonical_email, status, created_at) "
            + "SELECT ?, server_instance_id, synchronization_scope_id, ?, ?, 'pending', ? "
            + "FROM technical_synchronization_scope WHERE technical_default",
        account,
        EMAIL,
        EMAIL.toLowerCase(java.util.Locale.ROOT),
        Timestamp.from(START));
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void storesOnlyHashAndBindsPurposeAccountInstallationAndExactLifetime(
      ActionTokenPurpose purpose) {
    eligible(purpose);
    final var issued = issue(purpose);
    assertThat(AuthenticationSecrets.isRandomValue(issued.secret())).isTrue();
    assertThat(issued.recipient()).isEqualTo(EMAIL);
    assertThat(issued.expiresAt()).isEqualTo(START.plus(purpose.lifetime()));
    assertThat(jdbc.queryForMap("SELECT * FROM authentication_action_token"))
        .containsEntry("account_id", account)
        .containsEntry("purpose", purpose.value())
        .containsEntry("secret_verifier", AuthenticationSecrets.digest(issued.secret()));
    assertThat(
            jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM authentication_action_token t", String.class))
        .doesNotContain(issued.secret(), EMAIL);
    assertThat(issued.toString()).isEqualTo("IssuedToken[redacted]");
    var consumed = transactions.execute(status -> tokens.consume(purpose, issued.secret()));
    assertThat(consumed.account()).isEqualTo(account);
    assertThat(consumed.securityVersion()).isEqualTo(1);
    assertThat(consumed.installation())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT server_instance_id FROM technical_server_installation", UUID.class));
    assertThat(consumed.toString()).isEqualTo("ConsumedToken[redacted]");
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM authentication_account", String.class))
        .isEqualTo(purpose == ActionTokenPurpose.INVITATION ? "pending" : "active");
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void expiryEqualityIsInvalidAndOneNanosecondBeforeIsValid(ActionTokenPurpose purpose) {
    eligible(purpose);
    final var issued = issue(purpose);
    clock.now = issued.expiresAt().minusNanos(1);
    transactions.executeWithoutResult(
        status -> {
          tokens.consume(purpose, issued.secret());
          status.setRollbackOnly();
        });
    clock.now = issued.expiresAt();
    conflict(() -> consume(purpose, issued.secret()));
    clock.now = issued.expiresAt().plusNanos(1000);
    conflict(() -> consume(purpose, issued.secret()));
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
  }

  @Test
  void invitationCannotOutliveOriginalPendingReservationOrBeResentAtDeadline() {
    clock.now = START.plus(Duration.ofDays(7)).minusSeconds(60);
    final var issued = issue(ActionTokenPurpose.INVITATION);
    assertThat(issued.expiresAt()).isEqualTo(START.plus(Duration.ofDays(7)));
    clock.now = issued.expiresAt();
    conflict(() -> consume(ActionTokenPurpose.INVITATION, issued.secret()));
    conflict(() -> issue(ActionTokenPurpose.INVITATION));
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void wrongPurposeRandomSecretReplacementAndReplayShareConflict(ActionTokenPurpose purpose) {
    eligible(purpose);
    var old = issue(purpose);
    var wrong =
        purpose == ActionTokenPurpose.INVITATION
            ? ActionTokenPurpose.RESET
            : ActionTokenPurpose.INVITATION;
    conflict(() -> consume(wrong, old.secret()));
    conflict(() -> consume(purpose, AuthenticationSecrets.randomValue()));
    var current = issue(purpose);
    conflict(() -> consume(purpose, old.secret()));
    consume(purpose, current.secret());
    conflict(() -> consume(purpose, current.secret()));
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void rollbackRestoresConsumedTokenAndPriorAccountState(ActionTokenPurpose purpose) {
    eligible(purpose);
    final var issued = issue(purpose);
    var prior =
        jdbc.queryForMap(
            "SELECT status, password_verifier, security_version FROM authentication_account");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      tokens.consume(purpose, issued.secret());
                      jdbc.update(
                          "UPDATE authentication_account SET status = 'disabled', "
                              + "password_verifier = ?, security_version = 2",
                          VERIFIER);
                      throw new IllegalStateException("Synthetic rollback.");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject("SELECT security_version FROM authentication_account", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT status, password_verifier, security_version FROM authentication_account"))
        .isEqualTo(prior);
    consume(purpose, issued.secret());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "short",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "é"
      })
  void malformedSecretsNeverConsumeOrEnterDiagnostics(String secret) {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    conflict(() -> consume(ActionTokenPurpose.INVITATION, secret));
    consume(ActionTokenPurpose.INVITATION, issued.secret());
  }

  @Test
  void deferredCommitFailureCannotSendAndPreservesPrecedingToken(CapturedOutput output) {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    jdbc.execute(
        "CREATE FUNCTION reject_action_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'synthetic-commit-diagnostic-canary'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_action_commit AFTER INSERT OR UPDATE "
            + "ON authentication_action_token DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            + "EXECUTE FUNCTION reject_action_commit()");
    var sends = new AtomicInteger();
    var service =
        service(
            new ActionMailAdmission(clock),
            token -> {
              sends.incrementAndGet();
              return true;
            });
    assertThatThrownBy(
            () -> service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isInstanceOf(org.springframework.transaction.TransactionException.class);
    assertThat(sends).hasValue(0);
    consume(ActionTokenPurpose.INVITATION, issued.secret());
    assertThat(output.getAll())
        .doesNotContain(issued.secret(), EMAIL, "synthetic-commit-diagnostic-canary");
  }

  @Test
  void issuanceAndReplacementRollbackPreservePreviousCurrentToken() {
    var first = issue(ActionTokenPurpose.INVITATION);
    transactions.executeWithoutResult(
        status -> {
          tokens.issue(account, ActionTokenPurpose.INVITATION);
          status.setRollbackOnly();
        });
    consume(ActionTokenPurpose.INVITATION, first.secret());
    transactions.executeWithoutResult(
        status -> {
          tokens.issue(account, ActionTokenPurpose.INVITATION);
          status.setRollbackOnly();
        });
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void concurrentConsumptionHasExactlyOneCommittedWinner(ActionTokenPurpose purpose)
      throws Exception {
    eligible(purpose);
    final var issued = issue(purpose);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var attempts =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            if (!start.await(5, TimeUnit.SECONDS)) {
                              throw new IllegalStateException("Fixture timeout.");
                            }
                            try {
                              consume(purpose, issued.secret());
                              return true;
                            } catch (AuthenticationPreconditionException exception) {
                              return false;
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  attempts.get(0).get(10, TimeUnit.SECONDS),
                  attempts.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "disabled",
        "active-unverified",
        "wrong-mode",
        "replaced-verifier",
        "future",
        "extra-account",
        "extra-scope"
      })
  void currentEligibilityAndBindingAreRechecked(String state) {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    switch (state) {
      case "disabled" -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case "active-unverified" ->
          jdbc.update("UPDATE authentication_account SET status = 'active'");
      case "wrong-mode" ->
          jdbc.update(
              "UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
      case "replaced-verifier" ->
          jdbc.update(
              "UPDATE authentication_action_token SET secret_verifier = ?",
              AuthenticationSecrets.digest(AuthenticationSecrets.randomValue()));
      case "future" -> clock.now = START.minusSeconds(1);
      case "extra-account" -> {
        addAccounts(1, "active", START);
      }
      case "extra-scope" ->
          jdbc.update(
              "INSERT INTO technical_synchronization_scope (server_instance_id, technical_default) "
                  + "SELECT server_instance_id, FALSE FROM technical_server_installation");
      default -> throw new IllegalArgumentException("Unknown synthetic state.");
    }
    conflict(() -> consume(ActionTokenPurpose.INVITATION, issued.secret()));
  }

  @Test
  void schemaRejectsCrossInstallationBindingAndExcessLifetime() {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_action_token SET server_instance_id = ?",
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_action_token SET expires_at = ?",
                    Timestamp.from(START.plus(Duration.ofHours(24)).plusSeconds(1))))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE authentication_action_token SET purpose = 'login'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    consume(ActionTokenPurpose.INVITATION, issued.secret());
  }

  @Test
  void resetRequiresVerifiedActiveAccountAndPendingCannotIssueIt() {
    conflict(() -> issue(ActionTokenPurpose.RESET));
    jdbc.update(
        "UPDATE authentication_account SET status = 'active', password_verifier = ?", VERIFIER);
    conflict(() -> issue(ActionTokenPurpose.RESET));
    eligible(ActionTokenPurpose.RESET);
    final var issued = issue(ActionTokenPurpose.RESET);
    jdbc.update("UPDATE authentication_account SET status = 'disabled'");
    conflict(() -> consume(ActionTokenPurpose.RESET, issued.secret()));
  }

  @Test
  void invalidationRequiresTransactionAndRollsBackWithAccountMutation() {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    assertThatThrownBy(() -> tokens.invalidate(account)).isInstanceOf(IllegalStateException.class);
    transactions.executeWithoutResult(
        status -> {
          tokens.invalidate(account);
          status.setRollbackOnly();
        });
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
    transactions.executeWithoutResult(status -> tokens.invalidate(account));
    conflict(() -> consume(ActionTokenPurpose.INVITATION, issued.secret()));
  }

  @Test
  void persistenceRejectsMissingAndReadOnlyTransactions() {
    assertThatThrownBy(() -> tokens.issue(account, ActionTokenPurpose.INVITATION))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tokens.consume(ActionTokenPurpose.INVITATION, AuthenticationSecrets.randomValue()))
        .isInstanceOf(IllegalStateException.class);
    var readOnly = new TransactionTemplate(manager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> tokens.issue(account, ActionTokenPurpose.INVITATION)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sendsOnlyAfterCommitFailureRetainsReplacementAndDiagnosticsAreRedacted(
      CapturedOutput output) {
    var old = issue(ActionTokenPurpose.INVITATION);
    var seen = new java.util.ArrayList<IssuedToken>();
    var admission = new ActionMailAdmission(clock);
    var delivery =
        service(
            admission,
            token -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(
                      new JdbcTemplate(source)
                          .queryForObject(
                              "SELECT secret_verifier FROM authentication_action_token",
                              String.class))
                  .isEqualTo(AuthenticationSecrets.digest(token.secret()));
              seen.add(token);
              return false;
            });
    assertThat(delivery.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isEqualTo(ActionTokenDelivery.Outcome.UNAVAILABLE);
    conflict(() -> consume(ActionTokenPurpose.INVITATION, old.secret()));
    assertThat(delivery.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isEqualTo(ActionTokenDelivery.Outcome.LIMITED);
    assertThat(seen).hasSize(1);
    consume(ActionTokenPurpose.INVITATION, seen.getFirst().secret());
    assertThat(output.getAll()).doesNotContain(EMAIL, old.secret(), seen.getFirst().secret());
    assertThat(output.getAll()).contains("Action mail attempt completed.");
  }

  @Test
  void failedIssuanceNeverDeliversOrLeaksAndReleasesCapacity() {
    var calls = new AtomicInteger();
    var service =
        service(
            new ActionMailAdmission(clock),
            token -> {
              calls.incrementAndGet();
              return true;
            });
    for (String email : List.of("wrong@example.test", "other@example.test", "third@example.test")) {
      conflict(() -> service.issueAndDeliver(email, ActionTokenPurpose.INVITATION, () -> account));
    }
    assertThat(calls).hasValue(0);
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
    assertThat(service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
    assertThat(calls).hasValue(1);
  }

  @Test
  void concurrentDeliveryIsBoundedBeforeLookupAndDoesNotHoldAccountLocks() throws Exception {
    var entered = new CountDownLatch(2);
    var firstEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        service(
            new ActionMailAdmission(clock),
            token -> {
              entered.countDown();
              firstEntered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("Fixture timeout.");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Fixture interrupted.");
              }
              return true;
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account));
      try {
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        clock.now = START.plusSeconds(60);
        final var second =
            executor.submit(
                () -> service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        clock.now = START.plusSeconds(120);
        assertThatThrownBy(
                () ->
                    service.issueAndDeliver(
                        "unknown@example.test", ActionTokenPurpose.RESET, () -> UUID.randomUUID()))
            .isInstanceOf(com.hortinis.sync.authentication.AuthenticationOverloadedException.class);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
      } finally {
        release.countDown();
      }
    }
    assertThat(service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
  }

  @Test
  void provisionalUnknownAttemptAccountingReproducesOpenQ1RatherThanClaimingAvailability() {
    var sends = new AtomicInteger();
    var service =
        service(
            new ActionMailAdmission(clock),
            token -> {
              sends.incrementAndGet();
              return true;
            });
    for (int index = 0; index < 30; index++) {
      String email = "unknown" + index + "@example.test";
      conflict(
          () -> service.issueAndDeliver(email, ActionTokenPurpose.RESET, () -> UUID.randomUUID()));
    }
    assertThatThrownBy(
            () -> service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationOverloadedException.class);
    assertThat(sends).hasValue(0);
  }

  @Test
  void localBranchTimingEvidenceDoesNotClaimPublicResponseEqualization() throws Exception {
    var admission = new ActionMailAdmission(clock);
    long accepted;
    long quota;
    long refused;
    long unknown;
    try (var smtp = new LocalSmtpFixture(false, false)) {
      var service =
          service(
              admission,
              new com.hortinis.sync.authentication.SmtpActionMailDelivery(
                  smtp.sender(), "Hortinis@Example.test", "https://garden.example.test"));
      long start = System.nanoTime();
      assertThat(service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
          .isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
      accepted = (System.nanoTime() - start) / 1000;
      start = System.nanoTime();
      assertThat(service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
          .isEqualTo(ActionTokenDelivery.Outcome.LIMITED);
      quota = (System.nanoTime() - start) / 1000;
      assertThat(smtp.message()).contains("Subject: Hortinis invitation");
    }
    clock.now = START.plusSeconds(60);
    try (var smtp = new LocalSmtpFixture(false, true)) {
      var service =
          service(
              admission,
              new com.hortinis.sync.authentication.SmtpActionMailDelivery(
                  smtp.sender(), "Hortinis@Example.test", "https://garden.example.test"));
      long start = System.nanoTime();
      assertThat(service.issueAndDeliver(EMAIL, ActionTokenPurpose.INVITATION, () -> account))
          .isEqualTo(ActionTokenDelivery.Outcome.UNAVAILABLE);
      refused = (System.nanoTime() - start) / 1000;
      start = System.nanoTime();
      conflict(
          () ->
              service.issueAndDeliver(
                  "unknown@example.test", ActionTokenPurpose.RESET, () -> UUID.randomUUID()));
      unknown = (System.nanoTime() - start) / 1000;
      assertThat(smtp.message()).isEmpty();
    }
    System.out.println(
        "R3 synthetic internal branch microseconds: accepted="
            + accepted
            + ", refused="
            + refused
            + ", unknown="
            + unknown
            + ", quota="
            + quota);
  }

  @Test
  void callerSelectionRunsAfterAdmissionAndItsChangesShareTokenCommitOrRollback() {
    var admission = new ActionMailAdmission(clock);
    var sends = new AtomicInteger();
    var service =
        service(
            admission,
            token -> {
              sends.incrementAndGet();
              return true;
            });
    assertThat(
            service.issueAndDeliver(
                EMAIL,
                ActionTokenPurpose.INVITATION,
                () -> {
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isTrue();
                  jdbc.update("UPDATE authentication_account SET security_version = 2");
                  return account;
                }))
        .isEqualTo(ActionTokenDelivery.Outcome.ACCEPTED);
    assertThat(
            jdbc.queryForObject("SELECT security_version FROM authentication_account", Long.class))
        .isEqualTo(2);
    assertThat(
            service.issueAndDeliver(
                EMAIL,
                ActionTokenPurpose.INVITATION,
                () -> {
                  throw new AssertionError("Quota denial must precede selection.");
                }))
        .isEqualTo(ActionTokenDelivery.Outcome.LIMITED);
    clock.now = START.plusSeconds(60);
    assertThatThrownBy(
            () ->
                service.issueAndDeliver(
                    EMAIL,
                    ActionTokenPurpose.RESET,
                    () -> {
                      jdbc.update("UPDATE authentication_account SET security_version = 3");
                      return account;
                    }))
        .isInstanceOf(AuthenticationPreconditionException.class);
    assertThat(
            jdbc.queryForObject("SELECT security_version FROM authentication_account", Long.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
    assertThat(sends).hasValue(1);
  }

  @Test
  void enclosingTransactionCannotIssueOrSend() {
    var service =
        service(
            new ActionMailAdmission(clock),
            token -> {
              throw new AssertionError("Unexpected send.");
            });
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        service.issueAndDeliver(
                            EMAIL, ActionTokenPurpose.INVITATION, () -> account)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
  }

  @Test
  void cleanupHonorsExpiryPendingAgeBatchesAndPreservesScopeAndMode() {
    issue(ActionTokenPurpose.INVITATION);
    clock.now = START.plus(Duration.ofHours(24)).minusNanos(1);
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
    clock.now = START.plus(Duration.ofHours(24));
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isEqualTo(1);
    clock.now = START.plus(Duration.ofDays(7)).minusNanos(1);
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isEqualTo(1);
    clock.now = START.plus(Duration.ofDays(7));
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT access_mode FROM technical_server_installation", String.class))
        .isEqualTo("single-user-auth");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM technical_synchronization_scope", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void cleanupProcessesAtMost500ExpiredRowsPerCategoryPerTick() {
    addAccounts(501, "active", START);
    jdbc.update(
        "INSERT INTO authentication_action_token "
            + "SELECT account_id, server_instance_id, 'reset', "
            + "encode(sha256(account_id::text::bytea), 'hex'), ?, ? FROM "
            + "authentication_account WHERE status = 'active'",
        Timestamp.from(START),
        Timestamp.from(START.plusSeconds(600)));
    clock.now = START.plusSeconds(600);
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
    jdbc.update("DELETE FROM authentication_account");
    addAccounts(501, "pending", START);
    clock.now = START.plus(Duration.ofDays(7));
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isEqualTo(1);
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isZero();
  }

  @Test
  void failedCleanupRollsBackAndSanitizesScheduledAndStartupDiagnostics(CapturedOutput output) {
    final var issued = issue(ActionTokenPurpose.INVITATION);
    clock.now = START.plus(Duration.ofDays(7));
    jdbc.execute(
        "CREATE FUNCTION reject_pending_delete() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'synthetic-cleanup-diagnostic-canary'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_pending_delete BEFORE DELETE ON authentication_account "
            + "FOR EACH ROW EXECUTE FUNCTION reject_pending_delete()");
    var runner =
        new com.hortinis.sync.authentication.AuthenticationLifecycle(
            lifecycle,
            new com.hortinis.sync.authentication.AuthenticationAttemptLimiter(clock),
            new ActionMailAdmission(clock));
    runner.cleanupAttempts();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isEqualTo(1);
    assertThatThrownBy(runner::afterSingletonsInstantiated)
        .hasMessage("Authentication cleanup failed.")
        .hasNoCause();
    assertThat(output.getAll())
        .contains("Authentication cleanup failed.")
        .doesNotContain("synthetic-cleanup-diagnostic-canary", EMAIL, issued.secret());
  }

  @Test
  void cleanupSkipsLockedAccountsAndCatchesThemOnNextTick() throws Exception {
    issue(ActionTokenPurpose.INVITATION);
    clock.now = START.plus(Duration.ofDays(7));
    try (var executor = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(
          status -> {
            jdbc.queryForObject(
                "SELECT account_id FROM authentication_account FOR UPDATE", UUID.class);
            try {
              executor.submit(lifecycle::purgeActions).get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException exception) {
              throw new AssertionError("Cleanup did not skip a locked account.", exception);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Cleanup fixture interrupted.", exception);
            }
            assertThat(jdbc.queryForObject(COUNT, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isEqualTo(1);
          });
    }
    lifecycle.purgeActions();
    assertThat(jdbc.queryForObject(COUNT, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(ACCOUNTS, Integer.class)).isZero();
  }

  private void addAccounts(int count, String status, Instant created) {
    for (int index = 0; index < count; index++) {
      UUID scope =
          jdbc.queryForObject(
              "INSERT INTO technical_synchronization_scope "
                  + "(server_instance_id, technical_default) SELECT server_instance_id, FALSE "
                  + "FROM technical_server_installation RETURNING synchronization_scope_id",
              UUID.class);
      String email = "synthetic" + index + "@example.test";
      jdbc.update(
          "INSERT INTO authentication_account "
              + "(server_instance_id, synchronization_scope_id, "
              + "delivery_email, canonical_email, status, created_at) "
              + "SELECT server_instance_id, ?, ?, ?, ?, ? FROM technical_server_installation",
          scope,
          email,
          email,
          status,
          Timestamp.from(created));
    }
  }

  private void eligible(ActionTokenPurpose purpose) {
    if (purpose == ActionTokenPurpose.RESET) {
      jdbc.update(
          "UPDATE authentication_account SET status = 'active', verified_at = ?, "
              + "password_verifier = ?",
          Timestamp.from(START),
          VERIFIER);
    }
  }

  private ActionTokenDelivery service(ActionMailAdmission admission, ActionMailDelivery delivery) {
    return new ActionTokenDelivery(tokens, admission, delivery, manager);
  }

  private IssuedToken issue(ActionTokenPurpose purpose) {
    return transactions.execute(status -> tokens.issue(account, purpose));
  }

  private void consume(ActionTokenPurpose purpose, String secret) {
    transactions.executeWithoutResult(status -> tokens.consume(purpose, secret));
  }

  private static void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(AuthenticationPreconditionException.class)
        .hasMessage("The authentication precondition no longer holds.");
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now = START;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
