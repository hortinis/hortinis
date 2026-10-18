package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "hortinis.access.mode=single-user-no-auth",
      "hortinis.access.public-origin=http://localhost"
    })
class PostgreSqlSchemaIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void freshInstallationCreatesScopedKeysIndexesAndZeroCounter() {
    assertThat(flyway.info().applied()).hasSize(4);
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM technical_server_installation", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM technical_synchronization_scope WHERE technical_default",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_published_sequence FROM technical_synchronization_scope", Long.class))
        .isZero();
    for (String table :
        List.of(
            "technical_record",
            "accepted_technical_record_operation",
            "technical_record_change",
            "technical_record_tombstone",
            "retired_technical_record_identifier")) {
      List<String> columns =
          jdbc.query(
              "SELECT k.column_name FROM information_schema.key_column_usage k "
                  + "JOIN information_schema.table_constraints c ON c.constraint_name "
                  + "= k.constraint_name "
                  + "AND c.constraint_schema = k.constraint_schema WHERE c.table_schema = 'public' "
                  + "AND c.table_name = ? AND c.constraint_type = 'PRIMARY KEY' ORDER "
                  + "BY k.ordinal_position",
              (row, number) -> row.getString(1),
              table);
      assertThat(columns).hasSize(2).first().isEqualTo("synchronization_scope_id");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                    + "AND indexname = 'technical_record_change_pkey'",
                String.class))
        .contains("(synchronization_scope_id, server_sequence)");
    assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                    + "AND indexname = 'accepted_technical_record_operation_record'",
                String.class))
        .contains("(synchronization_scope_id, record_id)");
  }

  @Test
  void replacementSchemaExcludesRetiredAuthenticationStorage() {
    for (String retired :
        List.of(
            "authentication_credential",
            "authentication_challenge",
            "authentication_recovery_code",
            "authentication_operator_ticket",
            "spring_session_authority_reference")) {
      assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, retired))
          .isNull();
    }
  }

  @Test
  void rejectsMultipleInstallationsAndMultipleDefaultScopes() {
    assertThatThrownBy(
            () ->
                jdbc.update("INSERT INTO technical_server_installation (singleton) VALUES (FALSE)"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update("INSERT INTO technical_server_installation (singleton) VALUES (TRUE)"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO technical_synchronization_scope (server_instance_id, "
                        + "technical_default) "
                        + "SELECT server_instance_id, TRUE FROM technical_server_installation"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void emptyVersionThreeSchemaInstallsWithoutRewritingHistory() {
    String schema = isolatedSchema();
    Flyway previous = migration(schema, "3");
    previous.migrate();
    Flyway current = migration(schema, null);
    current.migrate();
    current.validate();
    assertThat(current.info().applied()).filteredOn(info -> info.getVersion() != null).hasSize(4);
    assertThat(current.info().current().getVersion().getVersion()).isEqualTo("4");
    assertThat(
            inSchema(schema)
                .queryForObject(
                    "SELECT last_published_sequence FROM technical_synchronization_scope",
                    Long.class))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "technical_record",
        "accepted_technical_record_operation",
        "technical_record_change",
        "technical_record_tombstone",
        "retired_technical_record_identifier"
      })
  void populatedTechnicalSchemasFailBeforeAnyPartitioningChange(String table) {
    String schema = isolatedSchema();
    Flyway previous = migration(schema, "3");
    previous.migrate();
    JdbcTemplate fixture = inSchema(schema);
    UUID record = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    switch (table) {
      case "technical_record" ->
          fixture.update("INSERT INTO technical_record VALUES (?, 1, 'synthetic')", record);
      case "accepted_technical_record_operation" -> insertLegacyReceipt(fixture, operation, record);
      case "technical_record_change" -> {
        insertLegacyReceipt(fixture, operation, record);
        fixture.update(
            "INSERT INTO technical_record_change (operation_id, record_id, "
                + "revision, value) VALUES (?, ?, 1, 'synthetic')",
            operation,
            record);
      }
      case "technical_record_tombstone" ->
          fixture.update("INSERT INTO technical_record_tombstone VALUES (?, 2, 1)", record);
      case "retired_technical_record_identifier" ->
          fixture.update("INSERT INTO retired_technical_record_identifier VALUES (?, 1)", record);
      default -> throw new AssertionError("Unknown synthetic table.");
    }
    assertThatThrownBy(() -> migration(schema, null).migrate())
        .isInstanceOf(RuntimeException.class);
    assertThat(previous.info().applied()).filteredOn(info -> info.getVersion() != null).hasSize(3);
    assertThat(fixture.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(1);
    assertThat(
            fixture.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name = 'technical_server_installation'",
                Integer.class,
                schema))
        .isZero();
    assertThat(
            fixture.queryForObject(
                "SELECT scope_id FROM technical_synchronization_scope", String.class))
        .isEqualTo("technical-records");
  }

  private static void insertLegacyReceipt(JdbcTemplate fixture, UUID operation, UUID record) {
    fixture.update(
        "INSERT INTO accepted_technical_record_operation "
            + "(operation_id, operation_kind, record_id, operation_value) "
            + "VALUES (?, 'create', ?, 'synthetic')",
        operation,
        record);
  }

  private static String isolatedSchema() {
    return "migration_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static Flyway migration(String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema);
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static JdbcTemplate inSchema(String schema) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword()));
  }
}
