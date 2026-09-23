package com.hortinis.sync.persistence;

import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.RecordTechnicalChange;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalChange;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalTombstone;
import com.hortinis.sync.protocol.TombstoneOperationResult;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(
    prefix = "hortinis.sync.persistence",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TechnicalRecordAcceptancePersistence {

  private static final int ONE_UPDATED_ROW = 1;
  private static final String TOMBSTONE_CHANGE_KIND = "tombstone";

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
                UUID.fromString(operation.operationId()),
                operation.kind(),
                UUID.fromString(operation.recordId()),
                operationValue(operation),
                expectedRevision(operation))
            .update()
        == 1;
  }

  public Optional<AcceptedOperationReceipt> findReceiptForUpdate(String operationId) {
    return jdbc.sql(
            "SELECT operation_kind, record_id::text, operation_value, expected_revision::text "
                + "FROM accepted_technical_record_operation WHERE operation_id = ? FOR UPDATE")
        .param(UUID.fromString(operationId))
        .query(
            (resultSet, rowNumber) ->
                new AcceptedOperationReceipt(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    resultSet.getString(3),
                    resultSet.getString(4)))
        .optional();
  }

  public Optional<TechnicalRecord> findRecordForUpdate(String recordId) {
    return jdbc.sql(
            "SELECT record_id::text, revision::text, value FROM technical_record "
                + "WHERE record_id = ? FOR UPDATE")
        .param(UUID.fromString(recordId))
        .query(
            (resultSet, rowNumber) ->
                new TechnicalRecord(
                    resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)))
        .optional();
  }

  public void lockRecord(String recordId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))")
        .param(recordId)
        .query()
        .singleValue();
  }

  public void insertRecord(CreateTechnicalRecordOperation operation) {
    jdbc.sql("INSERT INTO technical_record (record_id, revision, value) VALUES (?, 1, ?)")
        .params(UUID.fromString(operation.recordId()), operation.value())
        .update();
  }

  public boolean isIdentifierRetired(String recordId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM retired_technical_record_identifier "
                + "WHERE record_id = ?)")
        .param(UUID.fromString(recordId))
        .query(Boolean.class)
        .single();
  }

  public void replaceRecord(ReplaceTechnicalRecordOperation operation, String revision) {
    long nextRevision = new BigInteger(revision).longValueExact() + 1;
    jdbc.sql("UPDATE technical_record SET revision = ?, value = ? WHERE record_id = ?")
        .params(nextRevision, operation.value(), UUID.fromString(operation.recordId()))
        .update();
  }

  public long insertChange(String operationId, TechnicalRecord record) {
    return jdbc.sql(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value, change_kind) "
                + "VALUES (?, ?, ?, ?, 'record') "
                + "RETURNING server_sequence")
        .params(
            UUID.fromString(operationId),
            UUID.fromString(record.recordId()),
            new BigInteger(record.revision()).longValueExact(),
            record.value())
        .query(Long.class)
        .single();
  }

  public long insertTombstoneChange(String operationId, String recordId, String revision) {
    return jdbc.sql(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value, change_kind) "
                + "VALUES (?, ?, ?, NULL, 'tombstone') RETURNING server_sequence")
        .params(
            UUID.fromString(operationId),
            UUID.fromString(recordId),
            new BigInteger(revision).longValueExact())
        .query(Long.class)
        .single();
  }

  public void insertTombstone(TechnicalTombstone tombstone) {
    jdbc.sql(
            "INSERT INTO technical_record_tombstone "
                + "(record_id, revision, deleted_at_sequence) VALUES (?, ?, ?)")
        .params(
            UUID.fromString(tombstone.recordId()),
            new BigInteger(tombstone.revision()).longValueExact(),
            new BigInteger(tombstone.deletedAtSequence()).longValueExact())
        .update();
  }

  public void reserveIdentifier(String recordId, String sequence) {
    jdbc.sql(
            "INSERT INTO retired_technical_record_identifier (record_id, retired_at_sequence) "
                + "VALUES (?, ?)")
        .params(UUID.fromString(recordId), new BigInteger(sequence).longValueExact())
        .update();
  }

  public void deleteRecord(String recordId) {
    if (jdbc.sql("DELETE FROM technical_record WHERE record_id = ?")
            .param(UUID.fromString(recordId))
            .update()
        != ONE_UPDATED_ROW) {
      throw new IllegalStateException("The locked technical record disappeared during deletion.");
    }
  }

  public Optional<OperationResult> findResult(String operationId) {
    return jdbc.sql(
            "SELECT c.change_kind, c.operation_id::text, c.record_id::text, c.revision::text, "
                + "c.value, c.server_sequence::text FROM technical_record_change c "
                + "WHERE c.operation_id = ?")
        .param(UUID.fromString(operationId))
        .query((resultSet, rowNumber) -> toOperationResult(resultSet))
        .optional();
  }

  public List<TechnicalChange> findChangesAfter(long sequence, int limit) {
    return jdbc.sql(
            "SELECT c.change_kind, c.operation_id::text, c.record_id::text, c.revision::text, "
                + "c.value, c.server_sequence::text FROM technical_record_change c "
                + "WHERE c.server_sequence > ? ORDER BY c.server_sequence ASC LIMIT ?")
        .params(sequence, limit)
        .query((resultSet, rowNumber) -> toTechnicalChange(resultSet))
        .list();
  }

  private static Object expectedRevision(TechnicalRecordOperation operation) {
    if (operation instanceof ReplaceTechnicalRecordOperation replace) {
      return new BigInteger(replace.expectedRevision()).longValueExact();
    }
    if (operation instanceof DeleteTechnicalRecordOperation delete) {
      return new BigInteger(delete.expectedRevision()).longValueExact();
    }
    return null;
  }

  private static String operationValue(TechnicalRecordOperation operation) {
    if (operation instanceof CreateTechnicalRecordOperation create) {
      return create.value();
    }
    if (operation instanceof ReplaceTechnicalRecordOperation replace) {
      return replace.value();
    }
    return null;
  }

  private static OperationResult toOperationResult(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    if (TOMBSTONE_CHANGE_KIND.equals(resultSet.getString(1))) {
      String sequence = resultSet.getString(6);
      return new TombstoneOperationResult(
          "accepted",
          resultSet.getString(2),
          new TechnicalTombstone(resultSet.getString(3), resultSet.getString(4), sequence),
          sequence);
    }
    return new RecordOperationResult(
        "accepted",
        resultSet.getString(2),
        new TechnicalRecord(resultSet.getString(3), resultSet.getString(4), resultSet.getString(5)),
        resultSet.getString(6));
  }

  private static TechnicalChange toTechnicalChange(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    if (TOMBSTONE_CHANGE_KIND.equals(resultSet.getString(1))) {
      String sequence = resultSet.getString(6);
      return new TombstoneTechnicalChange(
          resultSet.getString(2),
          new TechnicalTombstone(resultSet.getString(3), resultSet.getString(4), sequence),
          sequence);
    }
    return new RecordTechnicalChange(
        resultSet.getString(2),
        new TechnicalRecord(resultSet.getString(3), resultSet.getString(4), resultSet.getString(5)),
        resultSet.getString(6));
  }

  public record AcceptedOperationReceipt(
      String kind, String recordId, String value, String expectedRevision) {}
}
