package com.hortinis.sync.protocol;

import java.util.Objects;

public record CreateTechnicalRecordOperation(
    OperationId operationId, RecordId recordId, String value) implements TechnicalRecordOperation {

  /** Enforces operation invariants before any workflow begins. */
  public CreateTechnicalRecordOperation {
    Objects.requireNonNull(operationId, "The operation identifier is required.");
    Objects.requireNonNull(recordId, "The record identifier is required.");
    if (!TechnicalRecordValueRules.isValid(value)) {
      throw new IllegalArgumentException("The technical record value is invalid.");
    }
  }

  @Override
  public String kind() {
    return "create";
  }
}
