package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
  private static final String TOMBSTONE_TABLE = "technical_record_tombstone";
  private static final String RETIRED_IDENTIFIER_TABLE = "retired_technical_record_identifier";
  private static final String SYNCHRONIZATION_SCOPE_TABLE = "technical_synchronization_scope";
  private static final String RECORD_ID_COLUMN = "record_id";
  private static final String OPERATION_ID_COLUMN = "operation_id";
  private static final String VALUE_COLUMN = "value";
  private static final String INSERT_INTO = "INSERT INTO ";
  private static final String ACCEPTED_CREATE_COLUMNS =
      "(operation_id, operation_kind, record_id, operation_value) ";
  private static final String ACCEPTED_CREATE_VALUES = "VALUES (?, 'create', ?, ?)";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;
  @Autowired private TechnicalRecordAcceptancePersistence persistence;

  @AfterEach
  void clearApplicationData() {
    jdbc.update(
        "TRUNCATE technical_record_change, technical_record_tombstone, "
            + "retired_technical_record_identifier, accepted_technical_record_operation, "
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
        .containsExactlyInAnyOrder(
            TECHNICAL_RECORD_TABLE,
            ACCEPTED_OPERATION_TABLE,
            CHANGE_TABLE,
            TOMBSTONE_TABLE,
            RETIRED_IDENTIFIER_TABLE,
            SYNCHRONIZATION_SCOPE_TABLE);
    assertThat(flyway.info().applied()).hasSize(3);
    assertThat(flyway.info().current())
        .isNotNull()
        .extracting(info -> info.getVersion().getVersion())
        .isEqualTo("3");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM technical_synchronization_scope WHERE scope_id = ?",
                Integer.class,
                "technical-records"))
        .isEqualTo(1);
  }

  @Test
  void populatedVersionOneDatabaseMigratesWithoutLosingAcceptedState() {
    String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    Flyway versionOne =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("1"))
            .load();
    versionOne.migrate();
    JdbcTemplate migrated =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    UUID operationId = UUID.randomUUID();
    UUID recordId = UUID.randomUUID();
    migrated.update(
        "INSERT INTO "
            + schema
            + ".accepted_technical_record_operation "
            + ACCEPTED_CREATE_COLUMNS
            + ACCEPTED_CREATE_VALUES,
        operationId,
        recordId,
        "retained value");
    migrated.update(
        "INSERT INTO " + schema + ".technical_record (record_id, revision, value) VALUES (?, 1, ?)",
        recordId,
        "retained value");

    Flyway latest =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .load();
    latest.migrate();

    assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(
            migrated.queryForObject(
                "SELECT value FROM " + schema + ".technical_record WHERE record_id = ?",
                String.class,
                recordId))
        .isEqualTo("retained value");
    assertThat(
            migrated.queryForObject(
                "SELECT count(*) FROM " + schema + ".technical_record_tombstone", Integer.class))
        .isZero();
    assertThat(
            migrated.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".technical_synchronization_scope WHERE scope_id = 'technical-records'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void scopeLockPreventsCommittedChangeFromOvertakingAnEarlierPublisher() throws Exception {
    UUID firstOperation = UUID.randomUUID();
    UUID firstRecord = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    UUID secondRecord = UUID.randomUUID();
    try (Connection first = POSTGRES.createConnection("");
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      first.setAutoCommit(false);
      insertReceiptAndRecord(first, firstOperation, firstRecord, "first");
      lockPublication(first);
      final long firstSequence = insertChange(first, firstOperation, firstRecord, "first");

      Future<Long> second =
          executor.submit(
              () -> {
                try (Connection connection = POSTGRES.createConnection("")) {
                  connection.setAutoCommit(false);
                  insertReceiptAndRecord(connection, secondOperation, secondRecord, "second");
                  lockPublication(connection);
                  long sequence = insertChange(connection, secondOperation, secondRecord, "second");
                  connection.commit();
                  return sequence;
                }
              });

      awaitBlockedPublisher(second);
      assertThat(persistence.findChangesAfter(0, 10)).isEmpty();
      first.commit();
      long secondSequence = second.get();

      assertThat(secondSequence).isGreaterThan(firstSequence);
      assertThat(persistence.findChangesAfter(0, 10))
          .extracting(change -> change.operationId())
          .containsExactly(firstOperation.toString(), secondOperation.toString());
    }
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
                              + ACCEPTED_CREATE_COLUMNS
                              + ACCEPTED_CREATE_VALUES,
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
            + ACCEPTED_CREATE_COLUMNS
            + ACCEPTED_CREATE_VALUES,
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

  private static void insertReceiptAndRecord(
      Connection connection, UUID operationId, UUID recordId, String value) throws Exception {
    try (PreparedStatement receipt =
            connection.prepareStatement(
                "INSERT INTO accepted_technical_record_operation "
                    + ACCEPTED_CREATE_COLUMNS
                    + ACCEPTED_CREATE_VALUES);
        PreparedStatement record =
            connection.prepareStatement(
                "INSERT INTO technical_record (record_id, revision, value) VALUES (?, 1, ?)")) {
      receipt.setObject(1, operationId);
      receipt.setObject(2, recordId);
      receipt.setString(3, value);
      receipt.executeUpdate();
      record.setObject(1, recordId);
      record.setString(2, value);
      record.executeUpdate();
    }
  }

  private static void lockPublication(Connection connection) throws Exception {
    try (PreparedStatement lock =
        connection.prepareStatement(
            "SELECT scope_id FROM technical_synchronization_scope "
                + "WHERE scope_id = 'technical-records' FOR UPDATE")) {
      lock.executeQuery().close();
    }
  }

  private static long insertChange(
      Connection connection, UUID operationId, UUID recordId, String value) throws Exception {
    try (PreparedStatement change =
        connection.prepareStatement(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value, change_kind) "
                + "VALUES (?, ?, 1, ?, 'record') RETURNING server_sequence")) {
      change.setObject(1, operationId);
      change.setObject(2, recordId);
      change.setString(3, value);
      try (ResultSet result = change.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void awaitBlockedPublisher(Future<Long> publisher) throws InterruptedException {
    for (int attempt = 0; attempt < 100 && !publisher.isDone(); attempt++) {
      Thread.sleep(10);
    }
    assertThat(publisher).as("later publisher must wait for the scope lock").isNotDone();
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
  }
}
