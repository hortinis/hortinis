package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.RecordId;
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
    OperationId createOperationId = OperationId.parse(UUID.randomUUID().toString());
    OperationId deleteOperationId = OperationId.parse(UUID.randomUUID().toString());
    RecordId recordId = RecordId.parse(UUID.randomUUID().toString());
    String jdbcUrl = POSTGRES.getJdbcUrl();
    OperationResult deletionResult;
    SynchronizationAccessContext originalContext;
    String originalCursor;

    try (ConfigurableApplicationContext first = startApplication(jdbcUrl)) {
      TechnicalRecordSynchronizationService synchronization =
          first.getBean(TechnicalRecordSynchronizationService.class);
      originalContext = first.getBean(SynchronizationAccessResolver.class).resolve();
      synchronization.submit(
          originalContext,
          new CreateTechnicalRecordOperation(createOperationId, recordId, "retained value"));
      deletionResult =
          synchronization.submit(
              originalContext,
              new DeleteTechnicalRecordOperation(
                  deleteOperationId, recordId, ExpectedRevision.parse("1")));
      originalCursor = synchronization.pull(originalContext, null).nextCursor();
    }

    try (ConfigurableApplicationContext second = startApplication(jdbcUrl)) {
      JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
      TechnicalRecordSynchronizationService synchronization =
          second.getBean(TechnicalRecordSynchronizationService.class);
      var restartedContext = second.getBean(SynchronizationAccessResolver.class).resolve();
      assertThat(restartedContext.serverInstanceId()).isEqualTo(originalContext.serverInstanceId());
      assertThat(restartedContext.synchronizationScopeId())
          .isEqualTo(originalContext.synchronizationScopeId());
      assertThat(synchronization.pull(restartedContext, originalCursor).changes()).isEmpty();
      assertThat(synchronization.pull(restartedContext, originalCursor).nextCursor())
          .isEqualTo(originalCursor);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
          .isEqualTo(4);
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
                  restartedContext,
                  new DeleteTechnicalRecordOperation(
                      deleteOperationId, recordId, ExpectedRevision.parse("1"))))
          .isEqualTo(deletionResult);
      assertThat(synchronization.pull(restartedContext, null).changes())
          .hasSize(2)
          .last()
          .isInstanceOf(TombstoneTechnicalChange.class);
      jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-auth'");
    }

    assertThatThrownBy(
            () -> {
              try (var unexpected = startApplication(jdbcUrl)) {
                throw new AssertionError("A mode downgrade must not start the application.");
              }
            })
        .hasRootCauseMessage("The configured access mode differs from committed setup.");
  }

  private ConfigurableApplicationContext startApplication(String jdbcUrl) {
    return new SpringApplicationBuilder(HortinisSyncApplication.class)
        .web(WebApplicationType.NONE)
        .properties(
            "hortinis.access.mode=single-user-no-auth",
            "hortinis.access.public-origin=http://localhost",
            "spring.datasource.url=" + jdbcUrl,
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword())
        .run();
  }
}
