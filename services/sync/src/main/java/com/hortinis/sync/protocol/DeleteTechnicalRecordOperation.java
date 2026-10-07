package com.hortinis.sync.protocol;

import java.util.Objects;

public record DeleteTechnicalRecordOperation(
    OperationId operationId, RecordId recordId, ExpectedRevision expectedRevision)
    implements TechnicalRecordOperation {

  /** Enforces operation invariants before any workflow begins. */
  public DeleteTechnicalRecordOperation {
    Objects.requireNonNull(operationId, "The operation identifier is required.");
    Objects.requireNonNull(recordId, "The record identifier is required.");
    Objects.requireNonNull(expectedRevision, "The expected revision is required.");
  }

  @Override
  public String kind() {
    return "delete";
  }
}
