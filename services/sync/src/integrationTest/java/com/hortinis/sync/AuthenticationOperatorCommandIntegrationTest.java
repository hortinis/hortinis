package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

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
  private static final String SETUP = "setup-ticket";
  private static final String OPERATOR = "operator";
  private static final String FAILURE = "Operator command failed.\n";
  private static final String TICKET_COUNT = "SELECT count(*) FROM authentication_operator_ticket";
  private static final String MODE_QUERY = "SELECT access_mode FROM technical_server_installation";
  private static final String CANARY = "synthetic-operator-diagnostic-canary";

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
  void retiredHostWorkflowsFailBeforeDatabaseFilesOrListener() throws Exception {
    for (String action : List.of(SETUP, "reconcile")) {
      var result =
          command(
              Map.of("SPRING_DATASOURCE_URL", "jdbc:unknown:" + CANARY),
              OPERATOR,
              action,
              directoryOption());
      assertThat(result.status()).isEqualTo(1);
      assertThat(result.output())
          .isEqualTo("Operator authentication workflows are unavailable until S9-R is complete.\n");
    }
    assertThat(jdbc.queryForObject(MODE_QUERY, String.class)).isNull();
    assertThat(count(TICKET_COUNT)).isZero();
    assertThat(count("SELECT count(*) FROM spring_session")).isZero();
    try (var entries = Files.list(directory)) {
      assertThat(entries.count()).isZero();
    }
  }

  @Test
  void invalidDispatchRemainsRedacted() throws Exception {
    var invalid = command(Map.of(), OPERATOR, SETUP, directoryOption(), "--ticket=" + CANARY);
    assertThat(invalid.status()).isEqualTo(2);
    assertThat(invalid.output()).isEqualTo("Invalid operator command.\n");
    var failed =
        commandMain(com.hortinis.operator.SyntheticUncaughtOperatorFailure.class, Map.of());
    assertThat(failed.status()).isEqualTo(1);
    assertThat(failed.output()).isEqualTo(FAILURE);
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
