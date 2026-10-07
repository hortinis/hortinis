package com.hortinis.sync.persistence;

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

@Repository
@SynchronizationPersistenceEnabled
public class TechnicalRecordAcceptancePersistence {

  private static final int ONE_UPDATED_ROW = 1;
  private static final String TECHNICAL_SYNCHRONIZATION_SCOPE = "technical-records";

  private final JdbcClient jdbc;

  public TechnicalRecordAcceptancePersistence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertReceipt(TechnicalRecordOperation operation) {
    return jdbc.sql(
                "INSERT INTO accepted_technical_record_operation "
                    + "(operation_id, operation_kind, record_id, operation_value, expected_revision) "
                    + "VALUES (?, ?, ?, ?, ?::bigint) ON CONFLICT (operation_id) DO NOTHING")
            .params(
                toJdbc(operation.operationId()),
                operation.kind(),
                toJdbc(operation.recordId()),
                operationValue(operation),
                expectedRevision(operation))
            .update()
        == 1;
  }

  public Optional<TechnicalRecordOperation> findReceiptForUpdate(OperationId operationId) {
    return jdbc.sql(
            "SELECT operation_kind, record_id::text, operation_value, expected_revision::text "
                + "FROM accepted_technical_record_operation WHERE operation_id = ? FOR UPDATE")
        .param(toJdbc(operationId))
        .query((resultSet, rowNumber) -> toOperation(operationId, resultSet))
        .optional();
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

  public Optional<TechnicalRecord> findRecordForUpdate(RecordId recordId) {
    return jdbc.sql(
            "SELECT record_id::text, revision, value FROM technical_record "
                + "WHERE record_id = ? FOR UPDATE")
        .param(toJdbc(recordId))
        .query(
            (resultSet, rowNumber) ->
                new TechnicalRecord(
                    RecordId.parse(resultSet.getString(1)),
                    new Revision(resultSet.getLong(2)),
                    resultSet.getString(3)))
        .optional();
  }

  public void lockRecord(RecordId recordId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))")
        .param(recordId.toWire())
        .query()
        .singleValue();
  }

  public void lockChangePublication() {
    jdbc.sql(
            "SELECT scope_id FROM technical_synchronization_scope "
                + "WHERE scope_id = ? FOR UPDATE")
        .param(TECHNICAL_SYNCHRONIZATION_SCOPE)
        .query(String.class)
        .single();
  }

  public void insertRecord(TechnicalRecord record) {
    jdbc.sql("INSERT INTO technical_record (record_id, revision, value) VALUES (?, ?, ?)")
        .params(toJdbc(record.recordId()), record.revision().value(), record.value())
        .update();
  }

  public boolean isIdentifierRetired(RecordId recordId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM retired_technical_record_identifier "
                + "WHERE record_id = ?)")
        .param(toJdbc(recordId))
        .query(Boolean.class)
        .single();
  }

  public void replaceRecord(TechnicalRecord record) {
    jdbc.sql("UPDATE technical_record SET revision = ?, value = ? WHERE record_id = ?")
        .params(record.revision().value(), record.value(), toJdbc(record.recordId()))
        .update();
  }

  public long insertChange(OperationId operationId, TechnicalRecord record) {
    return jdbc.sql(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value, change_kind) "
                + "VALUES (?, ?, ?, ?, 'record') "
                + "RETURNING server_sequence")
        .params(
            toJdbc(operationId),
            toJdbc(record.recordId()),
            record.revision().value(),
            record.value())
        .query(Long.class)
        .single();
  }

  public long insertTombstoneChange(OperationId operationId, RecordId recordId, Revision revision) {
    return jdbc.sql(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value, change_kind) "
                + "VALUES (?, ?, ?, NULL, 'tombstone') RETURNING server_sequence")
        .params(toJdbc(operationId), toJdbc(recordId), revision.value())
        .query(Long.class)
        .single();
  }

  public void insertTombstone(TechnicalTombstone tombstone) {
    jdbc.sql(
            "INSERT INTO technical_record_tombstone "
                + "(record_id, revision, deleted_at_sequence) VALUES (?, ?, ?)")
        .params(
            toJdbc(tombstone.recordId()),
            tombstone.revision().value(),
            new BigInteger(tombstone.deletedAtSequence()).longValueExact())
        .update();
  }

  public void reserveIdentifier(RecordId recordId, String sequence) {
    jdbc.sql(
            "INSERT INTO retired_technical_record_identifier (record_id, retired_at_sequence) "
                + "VALUES (?, ?)")
        .params(toJdbc(recordId), new BigInteger(sequence).longValueExact())
        .update();
  }

  public void deleteRecord(RecordId recordId) {
    if (jdbc.sql("DELETE FROM technical_record WHERE record_id = ?")
            .param(toJdbc(recordId))
            .update()
        != ONE_UPDATED_ROW) {
      throw new IllegalStateException("The locked technical record disappeared during deletion.");
    }
  }

  public Optional<OperationResult> findResult(OperationId operationId) {
    return jdbc.sql(
            "SELECT c.change_kind, c.operation_id::text, c.record_id::text, c.revision, "
                + "c.value, c.server_sequence::text FROM technical_record_change c "
                + "WHERE c.operation_id = ?")
        .param(toJdbc(operationId))
        .query((resultSet, rowNumber) -> toOperationResult(resultSet))
        .optional();
  }

  public List<TechnicalChange> findChangesAfter(long sequence, int limit) {
    return jdbc.sql(
            "SELECT c.change_kind, c.operation_id::text, c.record_id::text, c.revision, "
                + "c.value, c.server_sequence::text FROM technical_record_change c "
                + "WHERE c.server_sequence > ? ORDER BY c.server_sequence ASC LIMIT ?")
        .params(sequence, limit)
        .query((resultSet, rowNumber) -> toTechnicalChange(resultSet))
        .list();
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
