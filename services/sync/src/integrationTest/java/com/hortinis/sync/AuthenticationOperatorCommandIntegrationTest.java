package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual executable dispatch in isolated JVMs, fresh PostgreSQL, secure files and fixed
 * diagnostics.
 */
@Testcontainers
class AuthenticationOperatorCommandIntegrationTest {
  private static final String RECOVERY = "recovery-ticket";
  private static final String SETUP = "setup-ticket";
  private static final String OPERATOR = "operator";
  private static final String SUCCESS = "Operator ticket written to protected storage.\n";
  private static final String FAILURE = "Operator command failed.\n";
  private static final String TICKET_COUNT = "SELECT count(*) FROM authentication_operator_ticket";
  private static final String MODE_QUERY = "SELECT access_mode FROM technical_server_installation";
  private static final String CANARY = "synthetic-operator-diagnostic-canary";
  private static final String SYNTHETIC_VERIFIER =
      "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic-not-a-credential";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @TempDir Path directory;
  private JdbcTemplate jdbc;
  private String schema;

  @BeforeEach
  void freshDatabaseAndProtectedDirectory() throws Exception {
    schema = "operator_command_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(url(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void executableSelectsOnlyFreshAuthModeAndWritesOneVerifierBoundPrivateTicketWithoutListener()
      throws Exception {
    Result result;
    try (var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      result =
          command(
              Map.of("SERVER_PORT", Integer.toString(occupied.getLocalPort())),
              OPERATOR,
              SETUP,
              directoryOption());
    }
    assertThat(result.status()).isZero();
    assertThat(result.output()).isEqualTo(SUCCESS);
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isEqualTo("single-user-auth");
    assertThat(count(TICKET_COUNT)).isEqualTo(1);
    Path ticket;
    try (var entries = Files.list(directory)) {
      ticket = entries.findFirst().orElseThrow();
    }
    String secret = Files.readString(ticket).stripTrailing();
    assertThat(AuthenticationSecrets.isRandomValue(secret)).isTrue();
    assertThat(Files.getPosixFilePermissions(ticket))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(
            jdbc.queryForObject(
                "SELECT secret_verifier FROM authentication_operator_ticket", String.class))
        .isEqualTo(AuthenticationSecrets.digest(secret));
    assertThat(result.output()).doesNotContain(secret, schema, directory.toString());
    assertThat(count("SELECT count(*) FROM spring_session")).isZero();
    assertThat(count("SELECT count(*) FROM authentication_account")).isZero();
  }

  @Test
  void restartReplacementAndReconcileDeleteOldOrExpiredFilesWithoutReadingSecrets()
      throws Exception {
    assertThat(command(Map.of(), OPERATOR, SETUP, directoryOption()).status()).isZero();
    Path first;
    try (var entries = Files.list(directory)) {
      first = entries.findFirst().orElseThrow();
    }
    assertThat(command(Map.of(), OPERATOR, SETUP, directoryOption()).status()).isZero();
    assertThat(Files.exists(first)).isFalse();
    try (var entries = Files.list(directory)) {
      assertThat(entries.count()).isEqualTo(1);
    }
    jdbc.update(
        "UPDATE authentication_operator_ticket SET created_at = now() - INTERVAL '20 minutes', "
            + "expires_at = now() - INTERVAL '19 minutes'");
    var result = command(Map.of(), OPERATOR, "reconcile", directoryOption());
    assertThat(result.status()).isZero();
    assertThat(result.output()).isEqualTo("Operator ticket reconciliation completed.\n");
    assertThat(count(TICKET_COUNT)).isZero();
    try (var entries = Files.list(directory)) {
      assertThat(entries.count()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"single-user-no-auth", "multi-user-auth"})
  void committedModeCannotBeConvertedByHostSetup(String mode) throws Exception {
    jdbc.update("UPDATE technical_server_installation SET access_mode = ?", mode);
    var result = command(Map.of(), OPERATOR, SETUP, directoryOption());
    assertThat(result.status()).isEqualTo(1);
    assertThat(result.output()).isEqualTo(FAILURE);
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isEqualTo(mode);
    assertThat(count(TICKET_COUNT)).isZero();
    try (var entries = Files.list(directory)) {
      assertThat(entries.count()).isZero();
    }
  }

  @Test
  void exactExistingOwnerRecoveryDoesNotChangeItsPasswordKeysOrCreateAuthority() throws Exception {
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    UUID account = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authentication_account (account_id, "
            + "server_instance_id, synchronization_scope_id, "
            + "login_identifier, status, password_verifier, "
            + "user_handle, created_at) SELECT ?, server_instance_id, "
            + "synchronization_scope_id, 'synthetic-owner', 'active', "
            + "?, ?, now() FROM technical_synchronization_scope WHERE technical_default",
        account,
        SYNTHETIC_VERIFIER,
        new byte[32]);
    var result = command(Map.of(), OPERATOR, RECOVERY, "--account=" + account, directoryOption());
    assertThat(result.status()).isZero();
    assertThat(result.output()).isEqualTo(SUCCESS);
    assertThat(
            jdbc.queryForObject("SELECT target_id FROM authentication_operator_ticket", UUID.class))
        .isEqualTo(account);
    assertThat(
            jdbc.queryForObject(
                "SELECT password_verifier FROM authentication_account", String.class))
        .isEqualTo(SYNTHETIC_VERIFIER);
    assertThat(count("SELECT count(*) FROM authentication_session_authority")).isZero();
    assertThat(
            command(
                    Map.of(),
                    OPERATOR,
                    RECOVERY,
                    "--account=" + UUID.randomUUID(),
                    directoryOption())
                .output())
        .isEqualTo(FAILURE);
  }

  @Test
  void unsafeDirectoryFailureLeavesFreshModeAndDatabaseUnchangedAndPrintsNoSuppliedPath()
      throws Exception {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    var result = command(Map.of(), OPERATOR, SETUP, directoryOption());
    assertThat(result.status()).isEqualTo(1);
    assertThat(result.output()).isEqualTo(FAILURE);
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isNull();
    assertThat(count(TICKET_COUNT)).isZero();
  }

  @Test
  void databaseAndLoggingConfigurationFailuresCannotExposeDiagnosticCanaries() throws Exception {
    var result =
        command(
            Map.of(
                "SPRING_DATASOURCE_URL",
                "jdbc:unknown:" + CANARY,
                "SPRING_DATASOURCE_PASSWORD",
                CANARY,
                "LOGGING_LEVEL_ROOT",
                "TRACE",
                "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK",
                "TRACE"),
            OPERATOR,
            SETUP,
            directoryOption());
    assertThat(result.status()).isEqualTo(1);
    assertThat(result.output()).isEqualTo(FAILURE);
    assertThat(result.output()).doesNotContain(CANARY);
  }

  @Test
  void invalidOrMisplacedOperatorArgumentsNeverStartWebOrTouchDatabase() throws Exception {
    var invalid = command(Map.of(), OPERATOR, SETUP, directoryOption(), "--ticket=" + CANARY);
    assertThat(invalid.status()).isEqualTo(2);
    assertThat(invalid.output()).isEqualTo("Invalid operator command.\n");
    var misplaced = command(Map.of(), "--server.port=0", OPERATOR, SETUP, directoryOption());
    assertThat(misplaced.status()).isEqualTo(2);
    assertThat(count(TICKET_COUNT)).isZero();
  }

  @Test
  void failedCommitRestoresUnconfiguredModeAndDeletesOrphanedProtectedFile() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION reject_operator_ticket() RETURNS TRIGGER LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'Synthetic operator commit failure'; END $$");
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER reject_operator_ticket AFTER "
            + "INSERT ON authentication_operator_ticket "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE "
            + "FUNCTION reject_operator_ticket()");
    var failed = command(Map.of(), OPERATOR, SETUP, directoryOption());
    assertThat(failed.status()).isEqualTo(1);
    assertThat(failed.output()).isEqualTo(FAILURE);
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isNull();
    assertThat(count(TICKET_COUNT)).isZero();
    try (var entries = Files.list(directory)) {
      assertThat(entries.count()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "single-user-no-auth", "multi-user-auth"})
  void hostConfigurationRejectsMissingOrOtherModesBeforeDatabaseStartup(String mode)
      throws Exception {
    var failed = command(Map.of("HORTINIS_ACCESS_MODE", mode), OPERATOR, SETUP, directoryOption());
    assertThat(failed.status()).isEqualTo(1);
    assertThat(failed.output())
        .isEqualTo(
            "Operator command requires explicit single-user authentication configuration.\n");
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isNull();
    assertThat(count(TICKET_COUNT)).isZero();
  }

  @Test
  void unexpectedProcessFailureNeverPrintsCauseOrSecretMaterial() throws Exception {
    var failed =
        commandMain(com.hortinis.operator.SyntheticUncaughtOperatorFailure.class, Map.of());
    assertThat(failed.status()).isEqualTo(1);
    assertThat(failed.output()).isEqualTo(FAILURE);
    assertThat(failed.output()).doesNotContain(CANARY);
  }

  private Result command(Map<String, String> overrides, String... args) throws Exception {
    return commandMain(HortinisSyncApplication.class, overrides, args);
  }

  private Result commandMain(Class<?> main, Map<String, String> overrides, String... args)
      throws Exception {
    List<String> invocation = new ArrayList<>();
    invocation.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    invocation.add("-cp");
    invocation.add(Objects.requireNonNull(System.getProperty("hortinis.operator.test.classpath")));
    invocation.add(main.getName());
    invocation.addAll(List.of(args));
    var builder = new ProcessBuilder(invocation).redirectErrorStream(true);
    var environment = builder.environment();
    environment.clear();
    environment.put("HORTINIS_ACCESS_MODE", "single-user-auth");
    environment.put("SPRING_DATASOURCE_URL", url());
    environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
    environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
    environment.put("SPRING_FLYWAY_SCHEMAS", schema);
    environment.put("SPRING_FLYWAY_DEFAULT_SCHEMA", schema);
    environment.putAll(overrides);
    Process process = builder.start();
    try {
      if (!process.waitFor(45, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Synthetic operator process timeout.");
      }
      return new Result(
          process.exitValue(),
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      process.destroyForcibly();
    }
  }

  private String url() {
    return POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
  }

  private String directoryOption() {
    return "--directory=" + directory;
  }

  private int count(String query) {
    return Objects.requireNonNull(jdbc.queryForObject(query, Integer.class));
  }

  private record Result(int status, String output) {
    @Override
    public String toString() {
      return "OperatorCommandResult[redacted]";
    }
  }
}
