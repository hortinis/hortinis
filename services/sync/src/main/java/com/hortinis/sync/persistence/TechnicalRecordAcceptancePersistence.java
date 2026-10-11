package com.hortinis.sync.persistence;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.RecordId;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.RecordTechnicalChange;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.Revision;
import com.hortinis.sync.protocol.TechnicalChange;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalTombstone;
import com.hortinis.sync.protocol.TombstoneOperationResult;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Scoped SQL; mutation callers hold the scope acceptance lock in one service transaction. */
@Repository
@SynchronizationPersistenceEnabled
public class TechnicalRecordAcceptancePersistence {

  private static final int ONE_UPDATED_ROW = 1;
  private static final String OPERATION_PARAMETER = "operation";
  private static final String RECORD_PARAMETER = "record";
  private static final String VALUE_PARAMETER = "value";
  private static final String REVISION_PARAMETER = "revision";
  private static final String SEQUENCE_PARAMETER = "sequence";

  private final JdbcClient jdbc;
  private final SynchronizationScopePersistence scopes;

  /** Creates the scoped JDBC acceptance adapter. */
  public TechnicalRecordAcceptancePersistence(
      JdbcClient jdbc, SynchronizationScopePersistence scopes) {
    this.jdbc = jdbc;
    this.scopes = scopes;
  }

  private JdbcClient.StatementSpec scoped(SynchronizationAccessContext context, String sql) {
    scopes.requireScope(context);
    return jdbc.sql(sql).param("scope", UUID.fromString(context.synchronizationScopeId().value()));
  }

  /** Inserts the validated request identity within its scope; the caller holds the scope lock. */
  public boolean insertReceipt(
      SynchronizationAccessContext context, TechnicalRecordOperation operation) {
    return scoped(
                context,
                "INSERT INTO accepted_technical_record_operation "
                    + "(synchronization_scope_id, operation_id, operation_kind, record_id, "
                    + "operation_value, expected_revision) "
                    + "VALUES (:scope, :operation, :kind, :record, :value, :revision) "
                    + "ON CONFLICT (synchronization_scope_id, operation_id) DO NOTHING")
            .param(OPERATION_PARAMETER, toJdbc(operation.operationId()))
            .param("kind", operation.kind())
            .param(RECORD_PARAMETER, toJdbc(operation.recordId()))
            .param(VALUE_PARAMETER, operationValue(operation))
            .param(REVISION_PARAMETER, expectedRevision(operation))
            .update()
        == 1;
  }

  /** Reads only this scope's immutable accepted request. */
  public Optional<TechnicalRecordOperation> findReceipt(
      SynchronizationAccessContext context, OperationId operationId) {
    return scoped(
            context,
            "SELECT operation_kind, record_id::text, operation_value, expected_revision::text "
                + "FROM accepted_technical_record_operation "
                + "WHERE synchronization_scope_id = :scope AND operation_id = :operation")
        .param(OPERATION_PARAMETER, toJdbc(operationId))
        .query((resultSet, rowNumber) -> toOperation(operationId, resultSet))
        .optional();
  }

  /** Reads the selected projection; mutation workflows already hold the scope lock. */
  public Optional<TechnicalRecord> findRecord(
      SynchronizationAccessContext context, RecordId recordId) {
    return scoped(
            context,
            "SELECT record_id::text, revision, value FROM technical_record "
                + "WHERE synchronization_scope_id = :scope AND record_id = :record")
        .param(RECORD_PARAMETER, toJdbc(recordId))
        .query(
            (row, number) ->
                new TechnicalRecord(
                    RecordId.parse(row.getString(1)),
                    new Revision(row.getLong(2)),
                    row.getString(3)))
        .optional();
  }

  /** Creates a projection in the locked scope transaction. */
  public void insertRecord(SynchronizationAccessContext context, TechnicalRecord record) {
    scoped(
            context,
            "INSERT INTO technical_record (synchronization_scope_id, record_id, revision, value) "
                + "VALUES (:scope, :record, :revision, :value)")
        .param(RECORD_PARAMETER, toJdbc(record.recordId()))
        .param(REVISION_PARAMETER, record.revision().value())
        .param(VALUE_PARAMETER, record.value())
        .update();
  }

  /** Checks lifetime reservations only within the selected scope. */
  public boolean isIdentifierRetired(SynchronizationAccessContext context, RecordId recordId) {
    return scoped(
            context,
            "SELECT EXISTS (SELECT 1 FROM retired_technical_record_identifier "
                + "WHERE synchronization_scope_id = :scope AND record_id = :record)")
        .param(RECORD_PARAMETER, toJdbc(recordId))
        .query(Boolean.class)
        .single();
  }

  /** Replaces one projection in the locked scope transaction. */
  public void replaceRecord(SynchronizationAccessContext context, TechnicalRecord record) {
    if (scoped(
                context,
                "UPDATE technical_record SET revision = :revision, value = :value "
                    + "WHERE synchronization_scope_id = :scope AND record_id = :record")
            .param(REVISION_PARAMETER, record.revision().value())
            .param(VALUE_PARAMETER, record.value())
            .param(RECORD_PARAMETER, toJdbc(record.recordId()))
            .update()
        != ONE_UPDATED_ROW) {
      throw new IllegalStateException(
          "The locked technical record disappeared during replacement.");
    }
  }

  /** Allocates a transactional scope sequence and records an immutable live change. */
  public long insertChange(
      SynchronizationAccessContext context, OperationId operationId, TechnicalRecord record) {
    long sequence = scopes.allocateSequence(context);
    scoped(
            context,
            "INSERT INTO technical_record_change "
                + "(synchronization_scope_id, server_sequence, operation_id, record_id, "
                + "revision, value, change_kind) "
                + "VALUES (:scope, :sequence, :operation, :record, :revision, :value, 'record')")
        .param(SEQUENCE_PARAMETER, sequence)
        .param(OPERATION_PARAMETER, toJdbc(operationId))
        .param(RECORD_PARAMETER, toJdbc(record.recordId()))
        .param(REVISION_PARAMETER, record.revision().value())
        .param(VALUE_PARAMETER, record.value())
        .update();
    return sequence;
  }

  /** Allocates a transactional scope sequence and records an immutable deletion. */
  public long insertTombstoneChange(
      SynchronizationAccessContext context,
      OperationId operationId,
      RecordId recordId,
      Revision revision) {
    long sequence = scopes.allocateSequence(context);
    scoped(
            context,
            "INSERT INTO technical_record_change "
                + "(synchronization_scope_id, server_sequence, operation_id, record_id, "
                + "revision, value, change_kind) "
                + "VALUES (:scope, :sequence, :operation, :record, :revision, NULL, 'tombstone')")
        .param(SEQUENCE_PARAMETER, sequence)
        .param(OPERATION_PARAMETER, toJdbc(operationId))
        .param(RECORD_PARAMETER, toJdbc(recordId))
        .param(REVISION_PARAMETER, revision.value())
        .update();
    return sequence;
  }

  /** Persists deletion evidence in the locked scope transaction. */
  public void insertTombstone(SynchronizationAccessContext context, TechnicalTombstone tombstone) {
    scoped(
            context,
            "INSERT INTO technical_record_tombstone "
                + "(synchronization_scope_id, record_id, revision, deleted_at_sequence) "
                + "VALUES (:scope, :record, :revision, :sequence)")
        .param(RECORD_PARAMETER, toJdbc(tombstone.recordId()))
        .param(REVISION_PARAMETER, tombstone.revision().value())
        .param(SEQUENCE_PARAMETER, new BigInteger(tombstone.deletedAtSequence()).longValueExact())
        .update();
  }

  /** Reserves a deleted identity for the lifetime of its scope. */
  public void reserveIdentifier(
      SynchronizationAccessContext context, RecordId recordId, String sequence) {
    scoped(
            context,
            "INSERT INTO retired_technical_record_identifier "
                + "(synchronization_scope_id, record_id, retired_at_sequence) "
                + "VALUES (:scope, :record, :sequence)")
        .param(RECORD_PARAMETER, toJdbc(recordId))
        .param(SEQUENCE_PARAMETER, new BigInteger(sequence).longValueExact())
        .update();
  }

  /** Removes exactly one live projection from the locked scope. */
  public void deleteRecord(SynchronizationAccessContext context, RecordId recordId) {
    if (scoped(
                context,
                "DELETE FROM technical_record "
                    + "WHERE synchronization_scope_id = :scope AND record_id = :record")
            .param(RECORD_PARAMETER, toJdbc(recordId))
            .update()
        != ONE_UPDATED_ROW) {
      throw new IllegalStateException("The locked technical record disappeared during deletion.");
    }
  }

  /** Reconstructs the original accepted result from this scope's immutable journal. */
  public Optional<OperationResult> findResult(
      SynchronizationAccessContext context, OperationId operationId) {
    return scoped(
            context,
            "SELECT change_kind, operation_id::text, record_id::text, revision, "
                + "value, server_sequence::text "
                + "FROM technical_record_change WHERE synchronization_scope_id = :scope "
                + "AND operation_id = :operation")
        .param(OPERATION_PARAMETER, toJdbc(operationId))
        .query((row, number) -> toOperationResult(row))
        .optional();
  }

  /** Reads a bounded page ordered by numeric sequence within the selected scope. */
  public List<TechnicalChange> findChangesAfter(
      SynchronizationAccessContext context, long sequence, int limit) {
    return scoped(
            context,
            "SELECT change_kind, operation_id::text, record_id::text, revision, "
                + "value, server_sequence::text "
                + "FROM technical_record_change WHERE synchronization_scope_id = :scope "
                + "AND server_sequence > :sequence "
                + "ORDER BY technical_record_change.server_sequence ASC LIMIT :limit")
        .param(SEQUENCE_PARAMETER, sequence)
        .param("limit", limit)
        .query((row, number) -> toTechnicalChange(row))
        .list();
  }

  private static TechnicalRecordOperation toOperation(OperationId operationId, ResultSet resultSet)
      throws SQLException {
    RecordId recordId = RecordId.parse(resultSet.getString(2));
    return switch (resultSet.getString(1)) {
      case "create" ->
          new CreateTechnicalRecordOperation(operationId, recordId, resultSet.getString(3));
      case "replace" ->
          new ReplaceTechnicalRecordOperation(
              operationId,
              recordId,
              resultSet.getString(3),
              ExpectedRevision.parse(resultSet.getString(4)));
      case "delete" ->
          new DeleteTechnicalRecordOperation(
              operationId, recordId, ExpectedRevision.parse(resultSet.getString(4)));
      default -> throw new IllegalStateException("The stored operation kind is invalid.");
    };
  }

  private static Long expectedRevision(TechnicalRecordOperation operation) {
    return switch (operation) {
      case CreateTechnicalRecordOperation ignored -> null;
      case ReplaceTechnicalRecordOperation replace ->
          replace.expectedRevision().value().longValueExact();
      case DeleteTechnicalRecordOperation delete ->
          delete.expectedRevision().value().longValueExact();
    };
  }

  private static String operationValue(TechnicalRecordOperation operation) {
    return switch (operation) {
      case CreateTechnicalRecordOperation create -> create.value();
      case ReplaceTechnicalRecordOperation replace -> replace.value();
      case DeleteTechnicalRecordOperation ignored -> null;
    };
  }

  private static OperationResult toOperationResult(ResultSet resultSet) throws SQLException {
    OperationId operationId = OperationId.parse(resultSet.getString(2));
    RecordId recordId = RecordId.parse(resultSet.getString(3));
    Revision revision = new Revision(resultSet.getLong(4));
    String sequence = resultSet.getString(6);
    return switch (resultSet.getString(1)) {
      case "tombstone" ->
          new TombstoneOperationResult(
              "accepted",
              operationId,
              new TechnicalTombstone(recordId, revision, sequence),
              sequence);
      case "record" ->
          new RecordOperationResult(
              "accepted",
              operationId,
              new TechnicalRecord(recordId, revision, resultSet.getString(5)),
              sequence);
      default -> throw new IllegalStateException("The stored change kind is invalid.");
    };
  }

  private static TechnicalChange toTechnicalChange(ResultSet resultSet) throws SQLException {
    OperationId operationId = OperationId.parse(resultSet.getString(2));
    RecordId recordId = RecordId.parse(resultSet.getString(3));
    Revision revision = new Revision(resultSet.getLong(4));
    String sequence = resultSet.getString(6);
    return switch (resultSet.getString(1)) {
      case "tombstone" ->
          new TombstoneTechnicalChange(
              operationId, new TechnicalTombstone(recordId, revision, sequence), sequence);
      case "record" ->
          new RecordTechnicalChange(
              operationId,
              new TechnicalRecord(recordId, revision, resultSet.getString(5)),
              sequence);
      default -> throw new IllegalStateException("The stored change kind is invalid.");
    };
  }

  private static UUID toJdbc(OperationId operationId) {
    return UUID.fromString(operationId.toWire());
  }

  private static UUID toJdbc(RecordId recordId) {
    return UUID.fromString(recordId.toWire());
  }
}
