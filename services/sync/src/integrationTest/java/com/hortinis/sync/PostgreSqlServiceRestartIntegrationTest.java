package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
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
    String createOperationId = UUID.randomUUID().toString();
    String deleteOperationId = UUID.randomUUID().toString();
    String recordId = UUID.randomUUID().toString();
    String jdbcUrl = POSTGRES.getJdbcUrl();
    OperationResult deletionResult;

    try (ConfigurableApplicationContext first = startApplication(jdbcUrl)) {
      TechnicalRecordSynchronizationService synchronization =
          first.getBean(TechnicalRecordSynchronizationService.class);
      synchronization.submit(
          new CreateTechnicalRecordOperation(createOperationId, recordId, "retained value"));
      deletionResult =
          synchronization.submit(
              new DeleteTechnicalRecordOperation(deleteOperationId, recordId, "1"));
    }

    try (ConfigurableApplicationContext second = startApplication(jdbcUrl)) {
      JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
      TechnicalRecordSynchronizationService synchronization =
          second.getBean(TechnicalRecordSynchronizationService.class);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM technical_record_tombstone", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM retired_technical_record_identifier", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM accepted_technical_record_operation", Integer.class))
          .isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_record", Integer.class))
          .isZero();
      assertThat(
              synchronization.submit(
                  new DeleteTechnicalRecordOperation(deleteOperationId, recordId, "1")))
          .isEqualTo(deletionResult);
      assertThat(synchronization.pull(null).changes())
          .hasSize(2)
          .last()
          .isInstanceOf(TombstoneTechnicalChange.class);
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
