package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationOperatorTickets;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.OperatorTicketFiles;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Host issuance and file/database consistency; these tickets alone are never account authority. */
@Testcontainers
class AuthenticationOperatorTicketIntegrationTest {
  private static final Instant ISSUED = Instant.parse("2026-10-11T12:00:00Z");
  private static final String TICKET_COUNT = "SELECT count(*) FROM authentication_operator_ticket";
  private static final String SCOPE_ID =
      "SELECT synchronization_scope_id FROM technical_synchronization_scope "
          + "WHERE technical_default";
  private static final String SYNTHETIC_VERIFIER =
      "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic-not-a-credential";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @TempDir Path directory;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactionManager;

  @BeforeEach
  void freshSchema() throws Exception {
    String schema = "authentication_ticket_" + UUID.randomUUID().toString().replace("-", "");
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
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void controlledSetupStoresOnlyVerifierAndExactInstallationTarget() throws Exception {
    var issued = issuer(ISSUED).issueSetup();
    String secret = Files.readString(issued.file()).stripTrailing();
    assertThat(AuthenticationSecrets.isRandomValue(secret)).isTrue();
    assertThat(Files.getPosixFilePermissions(issued.file()))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(issued.toString()).doesNotContain(secret, issued.file().toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT secret_verifier FROM authentication_operator_ticket", String.class))
        .isEqualTo(AuthenticationSecrets.digest(secret));
    assertThat(
            jdbc.queryForObject(
                "SELECT purpose = 'setup' AND account_id IS NULL "
                    + "AND target_id = server_instance_id AND access_mode = 'single-user-auth' "
                    + "FROM authentication_operator_ticket",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT expires_at - created_at = INTERVAL '10 minutes' "
                    + "FROM authentication_operator_ticket",
                Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authentication_account", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_session_authority", Integer.class))
        .isZero();
  }

  @Test
  void replacementInvalidatesEarlierVerifierAndRemovesEarlierFile() throws Exception {
    var first = issuer(ISSUED).issueSetup();
    String oldSecret = Files.readString(first.file()).stripTrailing();
    var second = issuer(ISSUED.plusSeconds(1)).issueSetup();
    assertThat(second.file()).isNotEqualTo(first.file());
    assertThat(Files.exists(first.file())).isFalse();
    String verifier =
        jdbc.queryForObject(
            "SELECT secret_verifier FROM authentication_operator_ticket", String.class);
    assertThat(AuthenticationSecrets.matchesDigest(oldSecret, verifier)).isFalse();
    assertThat(
            AuthenticationSecrets.matchesDigest(
                Files.readString(second.file()).stripTrailing(), verifier))
        .isTrue();
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isEqualTo(1);
  }

  @Test
  void concurrentIssuersLeaveOneCommittedCurrentTicketAndFile() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> issuer(ISSUED).issueSetup());
      var second = executor.submit(() -> issuer(ISSUED).issueSetup());
      List<Path> returned =
          List.of(first.get(10, TimeUnit.SECONDS).file(), second.get(10, TimeUnit.SECONDS).file());
      issuer(ISSUED).reconcile();
      List<Path> files = ticketFiles();
      assertThat(files).hasSize(1);
      assertThat(returned).contains(files.getFirst());
      assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isEqualTo(1);
      String verifier =
          jdbc.queryForObject(
              "SELECT secret_verifier FROM authentication_operator_ticket", String.class);
      assertThat(
              AuthenticationSecrets.matchesDigest(
                  Files.readString(files.getFirst()).stripTrailing(), verifier))
          .isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sequence",
        "record",
        "receipt",
        "change",
        "tombstone",
        "reservation",
        "expectation",
        "extra-scope",
        "undesignated"
      })
  void freshSetupRefusesEveryRetainedTechnicalState(String trustedFixtureKind) throws Exception {
    UUID scope = jdbc.queryForObject(SCOPE_ID, UUID.class);
    UUID record = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    switch (trustedFixtureKind) {
      case "sequence" ->
          jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 1");
      case "record" ->
          jdbc.update("INSERT INTO technical_record VALUES (?, ?, 1, 'synthetic')", scope, record);
      case "receipt" -> receipt(scope, operation, record);
      case "change" -> {
        receipt(scope, operation, record);
        jdbc.update(
            "INSERT INTO technical_record_change "
                + "VALUES (?, 1, ?, ?, 1, 'synthetic', 'record')",
            scope,
            operation,
            record);
      }
      case "tombstone" ->
          jdbc.update("INSERT INTO technical_record_tombstone VALUES (?, ?, 1, 1)", scope, record);
      case "reservation" ->
          jdbc.update(
              "INSERT INTO retired_technical_record_identifier VALUES (?, ?, 1)", scope, record);
      case "expectation" ->
          jdbc.update(
              "INSERT INTO technical_exchange_expectation "
                  + "(synchronization_scope_id, expectation, server_instance_id, access_mode, "
                  + "account_id, phase, expires_at) SELECT ?, 'synthetic', server_instance_id, "
                  + "'single-user-auth', ?, 'observed', CURRENT_TIMESTAMP "
                  + "FROM technical_server_installation",
              scope,
              UUID.randomUUID());
      case "extra-scope" ->
          jdbc.update(
              "INSERT INTO technical_synchronization_scope "
                  + "(server_instance_id, technical_default) "
                  + "SELECT server_instance_id, FALSE FROM technical_server_installation");
      case "undesignated" ->
          jdbc.update("UPDATE technical_synchronization_scope SET technical_default = FALSE");
      default -> throw new AssertionError("Unknown synthetic retained state.");
    }
    assertThatThrownBy(() -> issuer(ISSUED).issueSetup())
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
    assertThat(ticketFiles()).isEmpty();
  }

  @Test
  void recoveryTargetsOnlyCurrentSoleActiveOwnerAndPreservesItsCredentials() throws Exception {
    UUID account = account();
    assertThatThrownBy(() -> issuer(ISSUED).issueSetup())
        .isInstanceOf(AuthenticationFailedException.class);
    assertThatThrownBy(() -> issuer(ISSUED).issueRecovery(UUID.randomUUID()))
        .isInstanceOf(AuthenticationFailedException.class);
    var ticket = issuer(ISSUED).issueRecovery(account);
    assertThat(Files.exists(ticket.file())).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT purpose FROM authentication_operator_ticket", String.class))
        .isEqualTo("operator");
    assertThat(
            jdbc.queryForObject("SELECT target_id FROM authentication_operator_ticket", UUID.class))
        .isEqualTo(account);
    assertThat(
            jdbc.queryForObject(
                "SELECT password_verifier FROM authentication_account", String.class))
        .isEqualTo(SYNTHETIC_VERIFIER);
    assertThat(
            jdbc.queryForObject("SELECT authority_epoch FROM authentication_account", Long.class))
        .isEqualTo(1);
    jdbc.update("UPDATE authentication_account SET status = 'disabled'");
    assertThatThrownBy(() -> issuer(ISSUED).issueRecovery(account))
        .isInstanceOf(AuthenticationFailedException.class);
  }

  @Test
  void exactExpiryAndClockRollbackRemoveRowsAndFiles() throws Exception {
    var ticket = issuer(ISSUED).issueSetup();
    issuer(ISSUED.plusSeconds(599)).reconcile();
    assertThat(Files.exists(ticket.file())).isTrue();
    issuer(ISSUED.plusSeconds(600)).reconcile();
    assertThat(Files.exists(ticket.file())).isFalse();
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
    var future = issuer(ISSUED).issueSetup();
    issuer(ISSUED.minusSeconds(1)).reconcile();
    assertThat(Files.exists(future.file())).isFalse();
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
  }

  @Test
  void restartedReconciliationRemovesOrphanWithoutReadingOrReplacingActiveFile() throws Exception {
    var live = issuer(ISSUED).issueSetup();
    Path orphan =
        new OperatorTicketFiles(directory)
            .create(UUID.randomUUID(), AuthenticationSecrets.randomValue());
    issuer(ISSUED).reconcile();
    assertThat(Files.exists(orphan)).isFalse();
    assertThat(ticketFiles()).containsExactly(live.file());
    issuer(ISSUED).reconcile();
    assertThat(ticketFiles()).containsExactly(live.file());
  }

  @Test
  void protectedFileFailureRollsBackTicketPublication() throws Exception {
    var issuer = issuer(ISSUED);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertThatThrownBy(issuer::issueSetup).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    issuer.reconcile();
    assertThat(ticketFiles()).isEmpty();
  }

  @Test
  void failedDatabaseCommitReconcilesTheFileCreatedBeforeCommit() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION reject_synthetic_ticket_commit() RETURNS trigger LANGUAGE plpgsql "
            + "AS $$ BEGIN RAISE EXCEPTION 'Synthetic ticket commit failure'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_synthetic_ticket_commit AFTER INSERT "
            + "ON authentication_operator_ticket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            + "EXECUTE FUNCTION reject_synthetic_ticket_commit()");
    assertThatThrownBy(() -> issuer(ISSUED).issueSetup())
        .isInstanceOf(org.springframework.transaction.TransactionException.class);
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
    assertThat(ticketFiles()).isEmpty();
  }

  @Test
  void hostCapabilityRejectsEnclosingTransactionAndUnselectedMode() throws Exception {
    var issuer = issuer(ISSUED);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> issuer.issueSetup()))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
    assertThatThrownBy(issuer::issueSetup).isInstanceOf(AuthenticationFailedException.class);
    assertThat(ticketFiles()).isEmpty();
    assertThat(jdbc.queryForObject(TICKET_COUNT, Integer.class)).isZero();
  }

  private AuthenticationOperatorTickets issuer(Instant now) {
    return new AuthenticationOperatorTickets(
        new AuthenticationOperatorTicketPersistence(
            JdbcClient.create(jdbc), Clock.fixed(now, ZoneOffset.UTC)),
        new OperatorTicketFiles(directory),
        transactionManager);
  }

  private List<Path> ticketFiles() throws Exception {
    try (var files = Files.list(directory)) {
      return files.sorted().toList();
    }
  }

  private void receipt(UUID scope, UUID operation, UUID record) {
    jdbc.update(
        "INSERT INTO accepted_technical_record_operation "
            + "VALUES (?, ?, 'create', ?, 'synthetic', NULL)",
        scope,
        operation,
        record);
  }

  private UUID account() {
    return jdbc.queryForObject(
        "INSERT INTO authentication_account "
            + "(server_instance_id, synchronization_scope_id, login_identifier, status, "
            + "password_verifier, user_handle, created_at) SELECT server_instance_id, "
            + "synchronization_scope_id, 'synthetic-gardener', 'active', ?, ?, CURRENT_TIMESTAMP "
            + "FROM technical_synchronization_scope WHERE technical_default RETURNING account_id",
        UUID.class,
        SYNTHETIC_VERIFIER,
        new byte[32]);
  }
}
