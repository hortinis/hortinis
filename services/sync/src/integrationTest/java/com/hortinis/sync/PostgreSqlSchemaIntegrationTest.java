package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostgreSqlSchemaIntegrationTest {

  private static final String POSTGRES_IMAGE = "postgres:18.0";
  private static final String TECHNICAL_RECORD_TABLE = "technical_record";
  private static final String ACCEPTED_OPERATION_TABLE = "accepted_technical_record_operation";
  private static final String CHANGE_TABLE = "technical_record_change";
  private static final String RECORD_ID_COLUMN = "record_id";
  private static final String OPERATION_ID_COLUMN = "operation_id";
  private static final String VALUE_COLUMN = "value";
  private static final String INSERT_INTO = "INSERT INTO ";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;

  @AfterEach
  void clearApplicationData() {
    jdbc.update(
        "TRUNCATE technical_record_change, accepted_technical_record_operation, "
            + "technical_record RESTART IDENTITY CASCADE");
  }

  @Test
  void migrationCreatesTechnicalSchemaOnRealPostgresDatabase() {
    List<String> applicationTables =
        jdbc.query(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                + "AND table_name <> 'flyway_schema_history' ORDER BY table_name",
            (resultSet, rowNumber) -> resultSet.getString(1));

    assertThat(applicationTables)
        .containsExactlyInAnyOrder(TECHNICAL_RECORD_TABLE, ACCEPTED_OPERATION_TABLE, CHANGE_TABLE);
    assertThat(flyway.info().applied()).hasSize(1);
    assertThat(flyway.info().current())
        .isNotNull()
        .extracting(info -> info.getVersion().getVersion())
        .isEqualTo("1");
  }

  @Test
  void validAcceptanceWritesCommitAllThreeRowsAndSequenceIsGenerated() {
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();

    transaction.executeWithoutResult(
        status -> insertAcceptanceBundle(operationId, recordId, "first value"));

    assertThat(count(TECHNICAL_RECORD_TABLE, RECORD_ID_COLUMN, recordId)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE, OPERATION_ID_COLUMN, operationId)).isEqualTo(1);
    assertThat(count(CHANGE_TABLE, OPERATION_ID_COLUMN, operationId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT server_sequence FROM " + CHANGE_TABLE, Long.class))
        .isPositive();
  }

  @Test
  void constraintFailureRollsBackEarlierAcceptanceWrites() {
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      jdbc.update(
                          INSERT_INTO
                              + ACCEPTED_OPERATION_TABLE
                              + " "
                              + "(operation_id, operation_kind, record_id, operation_value) "
                              + "VALUES (?, 'create', ?, ?)",
                          operationId,
                          recordId,
                          VALUE_COLUMN);
                      jdbc.update(
                          INSERT_INTO
                              + TECHNICAL_RECORD_TABLE
                              + " (record_id, revision, value) VALUES (?, 1, ?)",
                          recordId,
                          VALUE_COLUMN);
                      jdbc.update(
                          INSERT_INTO
                              + CHANGE_TABLE
                              + " "
                              + "(operation_id, record_id, revision, value) "
                              + "VALUES (?, ?, 1, ?)",
                          UUID.randomUUID(),
                          recordId,
                          VALUE_COLUMN);
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(count(TECHNICAL_RECORD_TABLE, RECORD_ID_COLUMN, recordId)).isZero();
    assertThat(count(ACCEPTED_OPERATION_TABLE, OPERATION_ID_COLUMN, operationId)).isZero();
    assertThat(count(CHANGE_TABLE, RECORD_ID_COLUMN, recordId)).isZero();
  }

  @Test
  void applicationFailureAfterAllWritesRollsBackTheWholeTransaction() {
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      insertAcceptanceBundle(operationId, recordId, VALUE_COLUMN);
                      throw new IllegalStateException("simulated acceptance failure");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count(TECHNICAL_RECORD_TABLE, RECORD_ID_COLUMN, recordId)).isZero();
    assertThat(count(ACCEPTED_OPERATION_TABLE, OPERATION_ID_COLUMN, operationId)).isZero();
    assertThat(count(CHANGE_TABLE, RECORD_ID_COLUMN, recordId)).isZero();
  }

  @Test
  void schemaConstraintsRejectInvalidOperationShapesAndDuplicateJournalEntries() {
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    INSERT_INTO
                        + ACCEPTED_OPERATION_TABLE
                        + " "
                        + "(operation_id, operation_kind, record_id, operation_value, "
                        + "expected_revision) "
                        + "VALUES (?, 'create', ?, ?, 1)",
                    operationId,
                    recordId,
                    VALUE_COLUMN))
        .isInstanceOf(RuntimeException.class);

    insertAcceptanceBundle(operationId, recordId, VALUE_COLUMN);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    INSERT_INTO
                        + CHANGE_TABLE
                        + " "
                        + "(operation_id, record_id, revision, value) VALUES (?, ?, 1, ?)",
                    operationId,
                    recordId,
                    "duplicate"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void journalSequencesIncreaseForAcceptedChanges() {
    UUID firstOperation = UUID.randomUUID();
    UUID firstRecord = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    UUID secondRecord = UUID.randomUUID();

    insertAcceptanceBundle(firstOperation, firstRecord, "first");
    insertAcceptanceBundle(secondOperation, secondRecord, "second");

    List<Long> sequences =
        jdbc.query(
            "SELECT server_sequence FROM " + CHANGE_TABLE + " ORDER BY server_sequence",
            (resultSet, rowNumber) -> resultSet.getLong(1));
    assertThat(sequences).hasSize(2);
    assertThat(sequences.get(1)).isGreaterThan(sequences.get(0));
  }

  private void insertAcceptanceBundle(UUID operationId, UUID recordId, String value) {
    jdbc.update(
        INSERT_INTO
            + ACCEPTED_OPERATION_TABLE
            + " "
            + "(operation_id, operation_kind, record_id, operation_value) "
            + "VALUES (?, 'create', ?, ?)",
        operationId,
        recordId,
        value);
    jdbc.update(
        INSERT_INTO + TECHNICAL_RECORD_TABLE + " (record_id, revision, value) VALUES (?, 1, ?)",
        recordId,
        value);
    jdbc.update(
        INSERT_INTO
            + CHANGE_TABLE
            + " (operation_id, record_id, revision, value) "
            + "VALUES (?, ?, 1, ?)",
        operationId,
        recordId,
        value);
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
  }
}
