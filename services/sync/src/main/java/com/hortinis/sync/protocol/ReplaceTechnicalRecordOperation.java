package com.hortinis.sync.protocol;

import java.util.Objects;

public record ReplaceTechnicalRecordOperation(
    OperationId operationId, RecordId recordId, String value, ExpectedRevision expectedRevision)
    implements TechnicalRecordOperation {

  /** Enforces operation invariants before any workflow begins. */
  public ReplaceTechnicalRecordOperation {
    Objects.requireNonNull(operationId, "The operation identifier is required.");
    Objects.requireNonNull(recordId, "The record identifier is required.");
    if (!TechnicalRecordValueRules.isValid(value)) {
      throw new IllegalArgumentException("The technical record value is invalid.");
    }
    Objects.requireNonNull(expectedRevision, "The expected revision is required.");
  }

  @Override
  public String kind() {
    return "replace";
  }
}
