package com.hortinis.sync.persistence;

import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import java.math.BigInteger;
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
                operation.value(),
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

  public void replaceRecord(ReplaceTechnicalRecordOperation operation, String revision) {
    long nextRevision = new BigInteger(revision).longValueExact() + 1;
    jdbc.sql("UPDATE technical_record SET revision = ?, value = ? WHERE record_id = ?")
        .params(nextRevision, operation.value(), UUID.fromString(operation.recordId()))
        .update();
  }

  public long insertChange(String operationId, TechnicalRecord record) {
    return jdbc.sql(
            "INSERT INTO technical_record_change "
                + "(operation_id, record_id, revision, value) VALUES (?, ?, ?, ?) "
                + "RETURNING server_sequence")
        .params(
            UUID.fromString(operationId),
            UUID.fromString(record.recordId()),
            new BigInteger(record.revision()).longValueExact(),
            record.value())
        .query(Long.class)
        .single();
  }

  public Optional<OperationResult> findResult(String operationId) {
    return jdbc.sql(
            "SELECT c.operation_id::text, c.record_id::text, c.revision::text, c.value, "
                + "c.server_sequence::text FROM technical_record_change c "
                + "WHERE c.operation_id = ?")
        .param(UUID.fromString(operationId))
        .query(
            (resultSet, rowNumber) ->
                new OperationResult(
                    "accepted",
                    resultSet.getString(1),
                    new TechnicalRecord(
                        resultSet.getString(2), resultSet.getString(3), resultSet.getString(4)),
                    resultSet.getString(5)))
        .optional();
  }

  private static Object expectedRevision(TechnicalRecordOperation operation) {
    if (operation instanceof ReplaceTechnicalRecordOperation replace) {
      return new BigInteger(replace.expectedRevision()).longValueExact();
    }
    return null;
  }

  public record AcceptedOperationReceipt(
      String kind, String recordId, String value, String expectedRevision) {}
}
