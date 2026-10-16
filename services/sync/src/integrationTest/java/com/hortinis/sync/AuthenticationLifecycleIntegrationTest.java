package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationLifecycle;
import com.hortinis.sync.authentication.AuthenticationOperatorTickets;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.OperatorTicketFiles;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Outcome;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical expiry and lock ordering against fresh production PostgreSQL storage. */
@Testcontainers
class AuthenticationLifecycleIntegrationTest {
  private static final Instant LOGIN = Instant.parse("2026-10-11T12:00:00Z");
  private static final String SYNTHETIC_VERIFIER =
      "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic-not-a-credential";
  private static final String AUTHORITY_COUNT =
      "SELECT (SELECT count(*) FROM authentication_session_authority) "
          + "+ (SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL)";
  private static final String EVENT_COUNT = "SELECT count(*) FROM authentication_security_event";
  private static final String TICKET_COUNT = "SELECT count(*) FROM authentication_operator_ticket";

  private static final String EXPIRE_NATIVE =
      "UPDATE spring_session SET last_access_time = 0, expiry_time = 0";

  private static final String TRANSPORT_COUNT = "SELECT count(*) FROM spring_session";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @TempDir Path directory;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactionManager;
  private UUID account;

  @BeforeEach
  void freshSchema() {
    String schema = "authentication_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
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
    account =
        jdbc.queryForObject(
            "INSERT INTO authentication_account (server_instance_id, synchronization_scope_id, "
                + "login_identifier, status, password_verifier, user_handle, created_at) "
                + "SELECT server_instance_id, synchronization_scope_id, 'synthetic-gardener', "
                + "'active', ?, ?, ? FROM technical_synchronization_scope WHERE technical_default "
                + "RETURNING account_id",
            UUID.class,
            SYNTHETIC_VERIFIER,
            new byte[32],
            timestamp(LOGIN));
  }

  @Test
  void nativeIdleExpiryDeletesTransportAndItsChallenge() {
    UUID parent = fullParent();
    transport(parent, true);
    challenge(parent, LOGIN.plusSeconds(1600), LOGIN.plusSeconds(1900));
    lifecycle(LOGIN.plusSeconds(1799)).purge();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM authentication_challenge")).isEqualTo(1);
    jdbc.update(EXPIRE_NATIVE);
    nativeCleanup();
    lifecycle(LOGIN.plusSeconds(1800)).purge();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(TRANSPORT_COUNT)).isZero();
    assertThat(count("SELECT count(*) FROM authentication_challenge")).isZero();
    lifecycle(LOGIN.plusSeconds(1800)).purge();
    assertThat(count(AUTHORITY_COUNT)).isZero();
  }

  @Test
  void factorDeadlineRemainsIndependentOfNativeRequestActivity() {
    UUID parent = fullParent();
    transport(parent, true);
    jdbc.update(
        "UPDATE spring_session SET last_access_time = ?, expiry_time = ?",
        Instant.now().toEpochMilli(),
        Instant.now().plusSeconds(1800).toEpochMilli());
    lifecycle(LOGIN.plusSeconds(43199)).purge();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    var expiredClock = Clock.fixed(LOGIN.plusSeconds(43200), ZoneOffset.UTC);
    assertThat(nativeFixture().persistence(expiredClock).list(account, 1)).isEmpty();
    lifecycle(LOGIN.plusSeconds(43200)).purge();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    jdbc.update(EXPIRE_NATIVE);
    nativeCleanup();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void partialDeadlineIsExactAndCleanupPreservesCurrentParents() {
    UUID full = fullParent();
    UUID partial = passwordParent(LOGIN);
    transport(full, true);
    transport(partial, true);
    lifecycle(LOGIN.plusSeconds(299)).purge();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(2);
    lifecycle(LOGIN.plusSeconds(300)).purge();
    assertThat(nativeFixture().persistence(Clock.fixed(LOGIN, ZoneOffset.UTC)).references(account))
        .containsExactly(full);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(
            nativeFixture()
                .persistence(Clock.fixed(LOGIN, ZoneOffset.UTC))
                .findFull(
                    full,
                    null,
                    com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions.Lock
                        .NONE)
                .orElseThrow()
                .bothFactorsAt())
        .isEqualTo(LOGIN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"status = 'disabled'", "authority_epoch = 2"})
  void staleAccountAuthorityIsDeniedBeforeNativeCleanup(String trustedFixtureChange) {
    UUID parent = fullParent();
    String verifier = verifier(parent);
    jdbc.update("UPDATE authentication_account SET " + trustedFixtureChange);
    var clock = Clock.fixed(LOGIN, ZoneOffset.UTC);
    var authority =
        new com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence(
            JdbcClient.create(jdbc), clock, nativeFixture().persistence(clock));
    assertThat(authority.findFull(verifier, parent)).isEmpty();
    lifecycle(LOGIN).purge();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM authentication_account")).isEqualTo(1);
  }

  @Test
  void futureParentCannotSurviveTrustedClockRollback() {
    transport(passwordParent(LOGIN.plusSeconds(1)), true);
    lifecycle(LOGIN).purge();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void expiredProofMaterialAndThirtyDayEventsArePurgedWithoutRefreshingAuthority() {
    UUID parent = fullParent();
    transport(parent, true);
    challenge(parent, LOGIN, LOGIN.plusSeconds(60));
    operatorTicket(null, LOGIN.minusSeconds(540), LOGIN.plusSeconds(60));
    String expiredCode = AuthenticationSecrets.digest(AuthenticationSecrets.randomValue());
    String liveCode = AuthenticationSecrets.digest(AuthenticationSecrets.randomValue());
    recoveryCode(expiredCode, LOGIN.plusSeconds(60));
    recoveryCode(liveCode, LOGIN.plusSeconds(61));
    Instant boundary = LOGIN.plusSeconds(60).minus(Duration.ofDays(30));
    event(boundary);
    event(boundary.plusNanos(1000));
    lifecycle(LOGIN.plusSeconds(60)).purge();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM authentication_challenge")).isZero();
    assertThat(count(TICKET_COUNT)).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT secret_verifier FROM authentication_recovery_code", String.class))
        .containsExactly(liveCode);
    assertThat(count(EVENT_COUNT)).isEqualTo(1);
    assertThat(
            nativeFixture()
                .persistence(Clock.fixed(LOGIN.plusSeconds(60), ZoneOffset.UTC))
                .findFull(
                    parent,
                    null,
                    com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions.Lock
                        .NONE)
                .orElseThrow()
                .bothFactorsAt())
        .isEqualTo(LOGIN);
    // A fresh instance repeats the same sweep without restoring or extending any state.
    lifecycle(LOGIN.plusSeconds(61)).purge();
    assertThat(count("SELECT count(*) FROM authentication_recovery_code")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"challenge", "operator_ticket", "recovery_code"})
  void futureProofMaterialIsRemovedAfterClockRollback(String trustedFixtureKind) {
    UUID parent = fullParent();
    transport(parent, true);
    switch (trustedFixtureKind) {
      case "challenge" -> challenge(parent, LOGIN.plusSeconds(1), LOGIN.plusSeconds(301));
      case "operator_ticket" -> operatorTicket(null, LOGIN.plusSeconds(1), LOGIN.plusSeconds(601));
      case "recovery_code" ->
          recoveryCode(
              AuthenticationSecrets.digest(AuthenticationSecrets.randomValue()),
              LOGIN.plus(Duration.ofDays(365)).plusSeconds(1));
      default -> throw new AssertionError("Unknown synthetic proof material.");
    }
    String query = "SELECT count(*) FROM authentication_" + trustedFixtureKind;
    assertThat(count(query)).isEqualTo(1);
    lifecycle(LOGIN).purge();
    assertThat(count(query)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
  }

  @Test
  void anonymousTransportUsesNativeExpiryWithoutApplicationOrphanScanning() {
    UUID parent = fullParent();
    transport(parent, true);
    transport(parent, false);
    transport(UUID.randomUUID(), false);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(3);
    lifecycle(LOGIN).purge();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(3);
    jdbc.update(
        "UPDATE spring_session SET last_access_time = 0, expiry_time = 0 "
            + "WHERE principal_name IS NULL");
    nativeCleanup();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
  }

  @Test
  void unownedAnonymousExpiryIsRemovedUnderInstallationLock() {
    UUID parent =
        jdbc.queryForObject(
            "INSERT INTO authentication_session_authority "
                + "(session_verifier, logout_target, state, created_at, expires_at) "
                + "VALUES (?, ?, 'anonymous', ?, ?) RETURNING session_reference",
            UUID.class,
            AuthenticationSecrets.digest(AuthenticationSecrets.randomValue()),
            AuthenticationSecrets.randomValue(),
            timestamp(LOGIN),
            timestamp(LOGIN.plusSeconds(300)));
    transport(parent, true);
    lifecycle(LOGIN.plusSeconds(299)).purge();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    lifecycle(LOGIN.plusSeconds(300)).purge();
    assertThat(count(TRANSPORT_COUNT)).isZero();
    assertThat(count(AUTHORITY_COUNT)).isZero();
  }

  @Test
  void cleanupRejectsEnclosingTransactionsAndDoesNotRunInUnselectedMode() {
    transport(fullParent(), true);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> lifecycle(LOGIN.plusSeconds(1800)).purge()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
    lifecycle(LOGIN.plusSeconds(1800)).purge();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
  }

  @Test
  void failedAuthorityDeletionRollsBackMatchingTransportDeletion() {
    UUID parent = passwordParent(LOGIN);
    transport(parent, true);
    jdbc.execute(
        "CREATE FUNCTION reject_synthetic_cleanup() RETURNS trigger LANGUAGE plpgsql "
            + "AS $$ BEGIN RAISE EXCEPTION 'Synthetic cleanup failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_synthetic_cleanup BEFORE DELETE "
            + "ON authentication_session_authority FOR EACH ROW "
            + "EXECUTE FUNCTION reject_synthetic_cleanup()");
    assertThatThrownBy(() -> lifecycle(LOGIN.plusSeconds(1800)).purge())
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    jdbc.execute("DROP TRIGGER reject_synthetic_cleanup ON authentication_session_authority");
    lifecycle(LOGIN.plusSeconds(1800)).purge();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void nativeCleanupWaitsForProtectedSessionTransactionBeforeDeletingItsSession() throws Exception {
    transport(fullParent(), true);
    jdbc.update(EXPIRE_NATIVE);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var protectedWork =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT primary_id FROM spring_session FOR SHARE", String.class);
                            locked.countDown();
                            await(release);
                          }));
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        final var cleanup = executor.submit(this::nativeCleanup);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
          waiting =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                          + "WHERE wait_event_type = 'Lock' "
                          + "AND query LIKE 'DELETE FROM SPRING_SESSION%')",
                      Boolean.class));
          if (!waiting) {
            Thread.sleep(10);
          }
        }
        assertThat(waiting).isTrue();
        assertThat(cleanup.isDone()).isFalse();
        release.countDown();
        protectedWork.get(10, TimeUnit.SECONDS);
        cleanup.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void cleanupWaitsForNativeActivityAndPreservesTheRenewedSession() throws Exception {
    transport(fullParent(), true);
    jdbc.update(EXPIRE_NATIVE);
    CountDownLatch refreshed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var activity =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.update(
                                "UPDATE spring_session SET last_access_time = ?, expiry_time = ?",
                                Instant.now().toEpochMilli(),
                                Instant.now().plusSeconds(1800).toEpochMilli());
                            refreshed.countDown();
                            await(release);
                          }));
      try {
        assertThat(refreshed.await(10, TimeUnit.SECONDS)).isTrue();
        final var cleanup = executor.submit(this::nativeCleanup);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
          waiting =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE "
                          + "wait_event_type = 'Lock' "
                          + "AND query LIKE 'DELETE FROM SPRING_SESSION%')",
                      Boolean.class));
          if (!waiting) {
            Thread.sleep(10);
          }
        }
        assertThat(waiting).isTrue();
        release.countDown();
        activity.get(10, TimeUnit.SECONDS);
        cleanup.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
  }

  @Test
  void protectedEventsStoreOnlyClosedCodesTrustedTimeAndFreshUnrelatedReferences() {
    var events = events();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              for (Event event : Event.values()) {
                events.append(event, Outcome.SUCCEEDED, Reason.COMPLETED);
                events.append(event, Outcome.DENIED, Reason.INVALID_PROOF);
                events.append(event, Outcome.UNAVAILABLE, Reason.UNAVAILABLE);
              }
            });
    assertThat(count(EVENT_COUNT)).isEqualTo(21);
    assertThat(count("SELECT count(DISTINCT request_reference) FROM authentication_security_event"))
        .isEqualTo(21);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT occurred_at FROM authentication_security_event", Timestamp.class))
        .containsExactly(timestamp(LOGIN));
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT event FROM authentication_security_event", String.class))
        .containsExactlyInAnyOrder(
            "setup",
            "login",
            "recovery",
            "step-up",
            "credential-change",
            "session-revoke",
            "logout");
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT outcome FROM authentication_security_event", String.class))
        .containsExactlyInAnyOrder("succeeded", "denied", "unavailable");
  }

  @Test
  void eventAndAuthorityPublicationRollBackTogether() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              fullParent();
              events().append(Event.LOGIN, Outcome.SUCCEEDED, Reason.COMPLETED);
              status.setRollbackOnly();
            });
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(EVENT_COUNT)).isZero();
  }

  @Test
  void eventWriterRejectsAbsentReadOnlyTransactionsAndInconsistentOutcomes() {
    var events = events();
    assertThatThrownBy(() -> events.append(Event.LOGIN, Outcome.DENIED, Reason.INVALID_PROOF))
        .isInstanceOf(IllegalStateException.class);
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.executeWithoutResult(
                    status -> events.append(Event.LOGIN, Outcome.DENIED, Reason.INVALID_PROOF)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> events.append(Event.LOGIN, Outcome.DENIED, Reason.COMPLETED)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            events.append(Event.LOGIN, Outcome.SUCCEEDED, Reason.INVALID_PROOF)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            events.append(Event.LOGIN, Outcome.UNAVAILABLE, Reason.PRECONDITION)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> events.append(null, Outcome.DENIED, Reason.INVALID_PROOF)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count(EVENT_COUNT)).isZero();
  }

  @Test
  void singletonStartupPurgesBeforeSpringStartsTheListeningLifecycle() throws Exception {
    UUID expired = passwordParent(LOGIN);
    transport(expired, true);
    var clock = new MutableClock();
    var tickets = protectedTickets(clock);
    final var issued = tickets.issueRecovery(account);
    jdbc.update("UPDATE authentication_operator_ticket SET claimed_session_reference = ?", expired);
    clock.advance(Duration.ofMinutes(5));
    var adapter = adapter(clock, tickets);
    var listener = new SyntheticListener();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AuthenticationLifecycle.class, () -> adapter);
      context.registerBean(SmartLifecycle.class, () -> listener);
      context.refresh();
      assertThat(listener.isRunning()).isTrue();
      assertThat(count(AUTHORITY_COUNT)).isZero();
      assertThat(count(TRANSPORT_COUNT)).isZero();
      assertThat(Files.exists(issued.file())).isFalse();
    }
    assertThat(listener.isRunning()).isFalse();
  }

  @Test
  void initialStorageFailurePreventsTheListeningLifecycle() throws Exception {
    var clock = new MutableClock();
    var adapter = adapter(clock, protectedTickets(clock));
    var listener = new SyntheticListener();
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AuthenticationLifecycle.class, () -> adapter);
      context.registerBean(SmartLifecycle.class, () -> listener);
      assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
      assertThat(listener.isRunning()).isFalse();
    }
  }

  @Test
  void scheduledSweepPurgesExpiredCeremoniesAndReconcilesConsumedFiles() throws Exception {
    UUID current = fullParent();
    UUID expired = passwordParent(LOGIN);
    transport(current, true);
    transport(expired, true);
    var clock = new MutableClock();
    var tickets = protectedTickets(clock);
    final var issued = tickets.issueRecovery(account);
    var adapter = adapter(clock, tickets);
    adapter.afterSingletonsInstantiated();
    clock.advance(Duration.ofMinutes(5));
    adapter.scheduledSweep();
    assertThat(nativeFixture().persistence(Clock.fixed(LOGIN, ZoneOffset.UTC)).references(account))
        .containsExactly(current);
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
    jdbc.update("DELETE FROM authentication_operator_ticket");
    adapter.scheduledSweep();
    assertThat(Files.exists(issued.file())).isFalse();
  }

  @Test
  void realThirtySecondSchedulePurgesExpiredDatabaseAndFilesWithoutRequests() throws Exception {
    UUID partial = passwordParent(LOGIN);
    transport(partial, true);
    var clock = new MutableClock();
    var tickets = protectedTickets(clock);
    final var issued = tickets.issueRecovery(account);
    var adapter = adapter(clock, tickets);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(ScheduledLifecycleConfiguration.class);
      context.registerBean(AuthenticationLifecycle.class, () -> adapter);
      context.refresh();
      assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
      clock.advance(Duration.ofMinutes(11));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
      while ((count(AUTHORITY_COUNT) != 0 || Files.exists(issued.file()))
          && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }
      assertThat(count(AUTHORITY_COUNT)).isZero();
      assertThat(count(TRANSPORT_COUNT)).isZero();
      assertThat(Files.exists(issued.file())).isFalse();
      assertThat(count(TICKET_COUNT)).isZero();
    }
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void scheduledFailureHasFixedDiagnosticsAndRetriesWithoutResurrectingExpiredState(
      CapturedOutput output) throws Exception {
    UUID partial = passwordParent(LOGIN);
    transport(partial, true);
    var clock = new MutableClock();
    var adapter = adapter(clock, protectedTickets(clock));
    adapter.afterSingletonsInstantiated();
    clock.advance(Duration.ofMinutes(5));
    jdbc.execute(
        "CREATE FUNCTION reject_lifecycle_adapter() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'synthetic-lifecycle-private-canary'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_lifecycle_adapter BEFORE DELETE ON authentication_session_authority "
            + "FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_adapter()");
    adapter.scheduledSweep();
    assertThat(count(AUTHORITY_COUNT)).isEqualTo(1);
    assertThat(output)
        .contains("Authentication cleanup failed.")
        .doesNotContain("synthetic-lifecycle-private-canary");
    jdbc.execute("DROP TRIGGER reject_lifecycle_adapter ON authentication_session_authority");
    adapter.scheduledSweep();
    assertThat(count(AUTHORITY_COUNT)).isZero();
    assertThat(count(TRANSPORT_COUNT)).isZero();
  }

  @Test
  void futureFactorsAndMissingReferenceDenyWithoutPhysicalCleanup() {
    UUID invalid = fullParent();
    nativeFixture().factors(invalid, LOGIN.plusSeconds(1));
    assertThat(nativeFixture().persistence(Clock.fixed(LOGIN, ZoneOffset.UTC)).list(account, 1))
        .isEmpty();
    nativeFixture().factors(invalid, LOGIN);
    jdbc.update(
        "UPDATE spring_session_attributes SET attribute_name = 'synthetic-wrong-name' "
            + "WHERE attribute_name = 'hortinis.authority'");
    assertThat(nativeFixture().persistence(Clock.fixed(LOGIN, ZoneOffset.UTC)).list(account, 1))
        .isEmpty();
    assertThat(count(TRANSPORT_COUNT)).isEqualTo(1);
  }

  private void nativeCleanup() {
    com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions.create(
            jdbc, transactionManager)
        .cleanUpExpiredSessions();
  }

  private AuthenticationOperatorTickets protectedTickets(Clock clock) throws Exception {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    return new AuthenticationOperatorTickets(
        new AuthenticationOperatorTicketPersistence(JdbcClient.create(jdbc), clock),
        new OperatorTicketFiles(directory),
        transactionManager);
  }

  private AuthenticationLifecycle adapter(Clock clock, AuthenticationOperatorTickets tickets) {
    return new AuthenticationLifecycle(
        new AuthenticationLifecyclePersistence(
            JdbcClient.create(jdbc),
            clock,
            transactionManager,
            new NativeAuthenticationSessions(jdbc, transactionManager).persistence(clock)),
        tickets,
        new AuthenticationAttemptLimiter(clock));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  static class ScheduledLifecycleConfiguration {}

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(LOGIN);

    void advance(Duration duration) {
      now.updateAndGet(value -> value.plus(duration));
    }

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
      return now.get();
    }
  }

  private final class SyntheticListener implements SmartLifecycle {
    private ServerSocket socket;

    @Override
    public boolean isRunning() {
      return socket != null && !socket.isClosed();
    }

    @Override
    public void start() {
      assertThat(count(AUTHORITY_COUNT)).isZero();
      assertThat(count(TRANSPORT_COUNT)).isZero();
      try {
        socket = new ServerSocket(0);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    @Override
    public void stop() {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      }
    }
  }

  private AuthenticationSecurityEventPersistence events() {
    return new AuthenticationSecurityEventPersistence(
        JdbcClient.create(jdbc), Clock.fixed(LOGIN, ZoneOffset.UTC));
  }

  private AuthenticationLifecyclePersistence lifecycle(Instant now) {
    return new AuthenticationLifecyclePersistence(
        JdbcClient.create(jdbc),
        Clock.fixed(now, ZoneOffset.UTC),
        transactionManager,
        new NativeAuthenticationSessions(jdbc, transactionManager)
            .persistence(Clock.fixed(now, ZoneOffset.UTC)));
  }

  private NativeAuthenticationSessions nativeSessionFixture;

  private NativeAuthenticationSessions nativeFixture() {
    if (nativeSessionFixture == null) {
      nativeSessionFixture = new NativeAuthenticationSessions(jdbc, transactionManager);
    }
    return nativeSessionFixture;
  }

  private UUID fullParent() {
    return nativeFixture().full(account, LOGIN);
  }

  private String verifier(UUID reference) {
    var limited =
        jdbc.queryForList(
            "SELECT session_verifier FROM authentication_session_authority WHERE "
                + "session_reference = ?",
            String.class,
            reference);
    return limited.isEmpty() ? nativeFixture().verifier(reference) : limited.getFirst();
  }

  private UUID passwordParent(Instant created) {
    return jdbc.queryForObject(
        "INSERT INTO authentication_session_authority (session_verifier, logout_target, "
            + "state, account_id, authority_epoch, created_at, expires_at) "
            + "VALUES (?, ?, 'password', ?, 1, ?, ?) RETURNING session_reference",
        UUID.class,
        AuthenticationSecrets.digest(AuthenticationSecrets.randomValue()),
        AuthenticationSecrets.randomValue(),
        account,
        timestamp(created),
        timestamp(created.plusSeconds(300)));
  }

  private void transport(UUID parent, boolean matchingVerifier) {
    if (matchingVerifier
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM spring_session s JOIN spring_session_attributes a "
                    + "ON a.session_primary_id = s.primary_id WHERE s.principal_name IS NOT NULL "
                    + "AND a.attribute_name = 'hortinis.authority' AND a.attribute_bytes = ?)",
                Boolean.class,
                SessionAttributeCodec.conversionService()
                    .convert(new SessionAuthorityReference(parent), byte[].class)))) {
      return;
    }
    String cookie = AuthenticationSecrets.randomValue();
    String primary = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO spring_session (primary_id, session_id, creation_time, "
            + "last_access_time, max_inactive_interval, expiry_time) "
            + "VALUES (?, ?, ?, ?, 1800, ?)",
        primary,
        cookie,
        LOGIN.toEpochMilli(),
        LOGIN.toEpochMilli(),
        LOGIN.plusSeconds(1800).toEpochMilli());
    jdbc.update(
        "INSERT INTO spring_session_attributes VALUES (?, 'hortinis.authority', ?)",
        primary,
        SessionAttributeCodec.conversionService()
            .convert(new SessionAuthorityReference(parent), byte[].class));
    if (matchingVerifier) {
      jdbc.update(
          "UPDATE authentication_session_authority SET session_verifier = ? "
              + "WHERE session_reference = ?",
          AuthenticationSecrets.digest(cookie),
          parent);
    }
  }

  private void challenge(UUID parent, Instant created, Instant expiry) {
    jdbc.update(
        "INSERT INTO authentication_challenge "
            + "(session_reference, challenge, purpose, created_at, expires_at, account_id) "
            + "VALUES (?, ?, 'credential', ?, ?, ?)",
        parent,
        new byte[32],
        timestamp(created),
        timestamp(expiry),
        account);
  }

  private void operatorTicket(UUID parent, Instant created, Instant expiry) {
    jdbc.update(
        "INSERT INTO authentication_operator_ticket "
            + "(secret_verifier, purpose, target_id, server_instance_id, access_mode, account_id, "
            + "created_at, expires_at, claimed_session_reference) "
            + "SELECT ?, 'operator', account_id, server_instance_id, 'single-user-auth', "
            + "account_id, ?, ?, ? FROM authentication_account WHERE account_id = ?",
        AuthenticationSecrets.digest(AuthenticationSecrets.randomValue()),
        timestamp(created),
        timestamp(expiry),
        parent,
        account);
  }

  private void recoveryCode(String digest, Instant expiry) {
    jdbc.update(
        "INSERT INTO authentication_recovery_code "
            + "(account_id, secret_verifier, created_at, expires_at) VALUES (?, ?, ?, ?)",
        account,
        digest,
        timestamp(expiry.minus(Duration.ofDays(365))),
        timestamp(expiry));
  }

  private void event(Instant occurred) {
    jdbc.update(
        "INSERT INTO authentication_security_event "
            + "(request_reference, occurred_at, event, outcome, reason) "
            + "VALUES (?, ?, 'login', 'denied', 'invalid-proof')",
        UUID.randomUUID(),
        timestamp(occurred));
  }

  private int count(String trustedQuery) {
    return java.util.Objects.requireNonNull(jdbc.queryForObject(trustedQuery, Integer.class));
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Synthetic protected transaction timed out.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Synthetic protected transaction interrupted.");
    }
  }
}
