package com.hortinis.sync.http;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.RecordTechnicalChange;
import com.hortinis.sync.protocol.TechnicalChange;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalTombstone;
import com.hortinis.sync.protocol.TombstoneOperationResult;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import java.util.List;

/** HTTP-only representations preserve the technical synchronization JSON contract. */
public final class TechnicalSynchronizationWire {

  private TechnicalSynchronizationWire() {}

  /** Converts a typed live record to its public string fields. */
  public static RecordBody toRecord(TechnicalRecord record) {
    return new RecordBody(record.recordId().toWire(), record.revision().toWire(), record.value());
  }

  /** Converts a typed tombstone without changing sequence representation. */
  public static TombstoneBody toTombstone(TechnicalTombstone tombstone) {
    return new TombstoneBody(
        tombstone.recordId().toWire(),
        tombstone.revision().toWire(),
        tombstone.deletedAtSequence());
  }

  /** Converts every accepted result variant explicitly. */
  public static ResultBody toResult(OperationResult result) {
    return switch (result) {
      case RecordOperationResult record ->
          new RecordResult(
              record.outcome(),
              record.operationId().toWire(),
              toRecord(record.record()),
              record.sequence());
      case TombstoneOperationResult tombstone ->
          new TombstoneResult(
              tombstone.outcome(),
              tombstone.operationId().toWire(),
              toTombstone(tombstone.tombstone()),
              tombstone.sequence());
    };
  }

  /** Converts an incremental page, preserving the opaque cursor and pagination. */
  public static ChangePageBody toPage(ChangePage page) {
    return new ChangePageBody(
        page.changes().stream().map(TechnicalSynchronizationWire::toChange).toList(),
        page.nextCursor(),
        page.hasMore());
  }

  private static ChangeBody toChange(TechnicalChange change) {
    return switch (change) {
      case RecordTechnicalChange record ->
          new RecordChange(
              record.operationId().toWire(), toRecord(record.record()), record.sequence());
      case TombstoneTechnicalChange tombstone ->
          new TombstoneChange(
              tombstone.operationId().toWire(),
              toTombstone(tombstone.tombstone()),
              tombstone.sequence());
    };
  }

  /** Public live-record fields. */
  public record RecordBody(String recordId, String revision, String value) {}

  /** Public retained-deletion fields. */
  public record TombstoneBody(String recordId, String revision, String deletedAtSequence) {}

  /** Accepted result variants are distinguished by their existing fields. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
  public sealed interface ResultBody permits RecordResult, TombstoneResult {}

  /** Accepted live-record result. */
  public record RecordResult(String outcome, String operationId, RecordBody record, String sequence)
      implements ResultBody {}

  /** Accepted deletion result. */
  public record TombstoneResult(
      String outcome, String operationId, TombstoneBody tombstone, String sequence)
      implements ResultBody {}

  /** Incremental change variants are distinguished by their existing fields. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
  public sealed interface ChangeBody permits RecordChange, TombstoneChange {}

  /** Accepted live-record change. */
  public record RecordChange(String operationId, RecordBody record, String sequence)
      implements ChangeBody {}

  /** Accepted deletion change. */
  public record TombstoneChange(String operationId, TombstoneBody tombstone, String sequence)
      implements ChangeBody {}

  /** Public incremental page. */
  public record ChangePageBody(List<ChangeBody> changes, String nextCursor, boolean hasMore) {}
}
