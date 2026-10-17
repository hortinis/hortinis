package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.r4.HostSmtpFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Fresh R4 evidence: actual executable dispatch, isolated Boot/JDBC and loopback STARTTLS SMTP. */
@Testcontainers
class AuthenticationOperatorCommandIntegrationTest {
  private static final String INVITE = "invite";
  private static final String ACCOUNT_ROWS = "SELECT * FROM authentication_account";
  private static final String TOKEN_VERIFIER =
      "SELECT secret_verifier FROM authentication_action_token";

  private static final String EMAIL = "Gardener@Example.test";
  private static final String MODE = "SELECT access_mode FROM technical_server_installation";
  private static final String ACCOUNTS = "SELECT count(*) FROM authentication_account";
  private static final String TOKENS = "SELECT count(*) FROM authentication_action_token";
  private static final String FAILURE = "Operator command failed.\n";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @TempDir Path directory;
  private JdbcTemplate jdbc;
  private String schema;

  @BeforeEach
  void freshDatabase() {
    schema = "r4_operator_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(url(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @Test
  void freshInviteResendPreservesRecipientAndScopeThenCancelReleasesOnlyReservation()
      throws Exception {
    try (var smtp = new HostSmtpFixture(directory)) {
      Result issued = command(smtp, INVITE, EMAIL + "\r\n");
      assertThat(issued.code()).isZero();
      assertThat(issued.output()).isEqualTo("Invitation committed and SMTP accepted.\n");
      assertThat(jdbc.queryForObject(MODE, String.class)).isEqualTo("single-user-auth");
      Map<String, Object> reservation = jdbc.queryForMap(ACCOUNT_ROWS);
      String old = jdbc.queryForObject(TOKEN_VERIFIER, String.class);
      Result resent =
          command(smtp, "resend-invitation", EMAIL.toUpperCase(java.util.Locale.ROOT) + "\n");
      assertThat(resent.code()).isZero();
      assertThat(jdbc.queryForMap(ACCOUNT_ROWS)).isEqualTo(reservation);
      assertThat(jdbc.queryForObject(TOKEN_VERIFIER, String.class)).isNotEqualTo(old);
      assertThat(smtp.messages())
          .hasSize(2)
          .allSatisfy(
              message -> assertThat(message).contains("To: " + EMAIL, "/accept-invitation#token="));
      assertThat(
              command(smtp, "cancel-invitation", EMAIL.toLowerCase(java.util.Locale.ROOT)).code())
          .isZero();
      assertThat(count(ACCOUNTS)).isZero();
      assertThat(count(TOKENS)).isZero();
      assertThat(count("SELECT count(*) FROM technical_synchronization_scope")).isEqualTo(1);
      assertThat(jdbc.queryForObject(MODE, String.class)).isEqualTo("single-user-auth");
      assertThat(count("SELECT count(*) FROM spring_session")).isZero();
      anonymousNativeSession();
      assertThat(command(smtp, INVITE, "Corrected@Example.test").code()).isZero();
      var corrected = jdbc.queryForMap(ACCOUNT_ROWS);
      assertThat(corrected.get("synchronization_scope_id"))
          .isEqualTo(reservation.get("synchronization_scope_id"));
      assertThat(corrected.get("account_id")).isNotEqualTo(reservation.get("account_id"));
      assertThat(corrected.get("delivery_email")).isEqualTo("Corrected@Example.test");
      assertThat(count("SELECT count(*) FROM spring_session")).isEqualTo(1);
      // Q2 evidence: immediate resends in separate processes pass their fresh local counters.
    }
  }

  @Test
  void firstSendFailureCommitsThenCanonicalEmailResendSucceeds() throws Exception {
    try (var smtp = new HostSmtpFixture(directory)) {
      smtp.rejectNext();
      var failed = command(smtp, INVITE, EMAIL);
      assertThat(failed.code()).isEqualTo(1);
      assertThat(failed.output())
          .isEqualTo("Invitation committed; delivery failed. Resend after the cooldown.\n");
      assertThat(count(ACCOUNTS)).isEqualTo(1);
      String old = jdbc.queryForObject(TOKEN_VERIFIER, String.class);
      assertThat(
              command(smtp, "resend-invitation", EMAIL.toLowerCase(java.util.Locale.ROOT)).code())
          .isZero();
      assertThat(smtp.messages()).hasSize(1);
      assertThat(jdbc.queryForObject(TOKEN_VERIFIER, String.class)).isNotEqualTo(old);
    }
  }

  @Test
  void concurrentSoleOwnerProvisioningHasOneWinnerWithoutModeOrScopeTransfer() throws Exception {
    try (var smtp = new HostSmtpFixture(directory);
        var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> command(smtp, INVITE, EMAIL));
      var b = executor.submit(() -> command(smtp, INVITE, "Other@Example.test"));
      assertThat(List.of(a.get(45, TimeUnit.SECONDS).code(), b.get(45, TimeUnit.SECONDS).code()))
          .containsExactlyInAnyOrder(0, 1);
      assertThat(count(ACCOUNTS)).isEqualTo(1);
      assertThat(count(TOKENS)).isEqualTo(1);
      assertThat(smtp.messages()).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "active", "disabled"})
  void resendAndCancelRejectNonPendingTargetsWithoutMutation(String state) throws Exception {
    try (var smtp = new HostSmtpFixture(directory)) {
      if (!state.equals("unknown")) {
        assertThat(command(smtp, INVITE, EMAIL).code()).isZero();
        jdbc.update("UPDATE authentication_account SET status = ?", state);
      }
      var before = jdbc.queryForList(ACCOUNT_ROWS);
      var tokens = jdbc.queryForList("SELECT * FROM authentication_action_token");
      for (String action : List.of("resend-invitation", "cancel-invitation")) {
        var denied = command(smtp, action, EMAIL);
        assertThat(denied.code()).isEqualTo(1);
        assertThat(denied.output()).isEqualTo(FAILURE);
      }
      assertThat(jdbc.queryForList(ACCOUNT_ROWS)).isEqualTo(before);
      assertThat(jdbc.queryForList("SELECT * FROM authentication_action_token")).isEqualTo(tokens);
    }
  }

  @Test
  void committedNoAuthInstallationCannotBeClaimed() throws Exception {
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
    try (var smtp = new HostSmtpFixture(directory)) {
      assertThat(command(smtp, INVITE, EMAIL).code()).isEqualTo(1);
      assertThat(count(ACCOUNTS)).isZero();
      assertThat(count(TOKENS)).isZero();
      assertThat(jdbc.queryForObject(MODE, String.class)).isEqualTo("single-user-no-auth");
    }
  }

  @Test
  void retiredCommandsAndInvalidInputFailBeforeDatabaseAndNeverReflectSecrets() throws Exception {
    for (String action : List.of("setup-ticket", "recovery-ticket", "reconcile", INVITE)) {
      var result =
          start(
              null,
              action,
              "synthetic-input-canary\n",
              Map.of("SPRING_DATASOURCE_URL", "jdbc:unknown:canary"));
      assertThat(result.code()).isEqualTo(2);
      assertThat(result.output()).isEqualTo("Invalid operator command or input.\n");
    }
    assertThat(jdbc.queryForObject(MODE, String.class)).isNull();
    assertThat(count(ACCOUNTS)).isZero();
  }

  @Test
  void tokenIssuanceFailureRollsBackModeReservationAndPendingAccountTogether() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION synthetic_issuance_failure() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic issuance canary'; END $$");
    jdbc.execute(
        "CREATE TRIGGER synthetic_issuance_failure BEFORE INSERT ON "
            + "authentication_action_token FOR EACH ROW "
            + "EXECUTE FUNCTION synthetic_issuance_failure()");
    try (var smtp = new HostSmtpFixture(directory)) {
      var failed = command(smtp, INVITE, EMAIL);
      assertThat(failed.code()).isEqualTo(1);
      assertThat(failed.output()).isEqualTo(FAILURE);
      assertThat(jdbc.queryForObject(MODE, String.class)).isNull();
      assertThat(count(ACCOUNTS)).isZero();
      assertThat(count(TOKENS)).isZero();
      assertThat(smtp.messages()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"published", "undesignated", "unconfigured-session"})
  void nonemptyOrUndesignatedSoleScopeCannotBeReserved(String state) throws Exception {
    if (state.equals("unconfigured-session")) {
      anonymousNativeSession();
    } else {
      jdbc.update(
          state.equals("published")
              ? "UPDATE technical_synchronization_scope SET last_published_sequence = 1"
              : "UPDATE technical_synchronization_scope SET technical_default = false");
    }
    try (var smtp = new HostSmtpFixture(directory)) {
      assertThat(command(smtp, INVITE, EMAIL).code()).isEqualTo(1);
      assertThat(count(ACCOUNTS)).isZero();
      assertThat(count(TOKENS)).isZero();
      assertThat(jdbc.queryForObject(MODE, String.class)).isNull();
      assertThat(smtp.messages()).isEmpty();
    }
  }

  @Test
  void environmentCannotTurnHostCommandIntoWebApplication() throws Exception {
    var result =
        start(
            null,
            INVITE,
            EMAIL,
            Map.of("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet", "SERVER_PORT", "0"));
    assertThat(result.code()).isEqualTo(1);
    assertThat(result.output()).isEqualTo(FAILURE);
    assertThat(jdbc.queryForObject(MODE, String.class)).isNull();
    assertThat(count(ACCOUNTS)).isZero();
  }

  @Test
  void frameworkConfigurationFailuresRemainRedactedEvenWithVerboseLoggerSettings()
      throws Exception {
    var result =
        start(
            null,
            INVITE,
            EMAIL,
            Map.of(
                "SPRING_DATASOURCE_URL",
                "jdbc:unknown:synthetic-configuration-canary",
                "LOGGING_LEVEL_ROOT",
                "TRACE"));
    assertThat(result.code()).isEqualTo(1);
    assertThat(result.output()).isEqualTo(FAILURE);
    assertThat(jdbc.queryForObject(MODE, String.class)).isNull();
    assertThat(count(ACCOUNTS)).isZero();
  }

  private void anonymousNativeSession() {
    long now = System.currentTimeMillis();
    jdbc.update(
        "INSERT INTO spring_session (primary_id, session_id, creation_time, "
            + "last_access_time, max_inactive_interval, expiry_time) VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        com.hortinis.sync.authentication.AuthenticationSecrets.randomValue(),
        now,
        now,
        1800,
        now + 1800000);
  }

  private Result command(HostSmtpFixture smtp, String action, String input) throws Exception {
    return start(smtp, action, input, Map.of());
  }

  private Result start(
      HostSmtpFixture smtp, String action, String input, Map<String, String> overrides)
      throws Exception {
    var invocation = new ArrayList<String>();
    invocation.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    if (smtp != null) {
      invocation.addAll(smtp.trustArguments());
    }
    invocation.addAll(
        List.of(
            "-cp",
            Objects.requireNonNull(System.getProperty("hortinis.operator.test.classpath")),
            HortinisSyncApplication.class.getName(),
            "operator",
            action));
    var builder = new ProcessBuilder(invocation).redirectErrorStream(true);
    var env = builder.environment();
    env.clear();
    env.put("HORTINIS_ACCESS_MODE", "single-user-auth");
    env.put("HORTINIS_ACCESS_PUBLIC_ORIGIN", "https://localhost");
    env.put("SPRING_DATASOURCE_URL", url());
    env.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
    env.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
    env.put("SPRING_FLYWAY_SCHEMAS", schema);
    env.put("SPRING_FLYWAY_DEFAULT_SCHEMA", schema);
    env.put("SPRING_MAIL_HOST", "localhost");
    env.put("SPRING_MAIL_PORT", smtp == null ? "9" : Integer.toString(smtp.port()));
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH", "false");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE", "true");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED", "true");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_CHECKSERVERIDENTITY", "true");
    env.put("HORTINIS_AUTHENTICATION_MAIL_FROM", "Hortinis@Example.test");
    env.putAll(overrides);
    var process = builder.start();
    try {
      process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      if (!process.waitFor(40, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Synthetic command timeout.");
      }
      return new Result(
          process.exitValue(),
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      process.destroyForcibly();
    }
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private String url() {
    return POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
  }

  private record Result(int code, String output) {
    @Override
    public String toString() {
      return "R4CommandResult[redacted]";
    }
  }
}
