package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgreSqlServiceRestartIntegrationTest {

  private static final String POSTGRES_IMAGE = "postgres:18.0";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

  @Test
  void restartRetainsDataAndDoesNotReapplyFlywayMigration() {
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();
    String jdbcUrl = POSTGRES.getJdbcUrl();

    try (ConfigurableApplicationContext first = startApplication(jdbcUrl)) {
      JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
      jdbc.update(
          "INSERT INTO accepted_technical_record_operation "
              + "(operation_id, operation_kind, record_id, operation_value) "
              + "VALUES (?, 'create', ?, ?)",
          operationId,
          recordId,
          "retained value");
      jdbc.update(
          "INSERT INTO technical_record (record_id, revision, value) VALUES (?, 1, ?)",
          recordId,
          "retained value");
    }

    try (ConfigurableApplicationContext second = startApplication(jdbcUrl)) {
      JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT value FROM technical_record WHERE record_id = ?", String.class, recordId))
          .isEqualTo("retained value");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM technical_record_tombstone", Integer.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_record", Integer.class))
          .isEqualTo(1);
    }
  }

  private ConfigurableApplicationContext startApplication(String jdbcUrl) {
    return new SpringApplicationBuilder(HortinisSyncApplication.class)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.datasource.url=" + jdbcUrl,
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword())
        .run();
  }
}
