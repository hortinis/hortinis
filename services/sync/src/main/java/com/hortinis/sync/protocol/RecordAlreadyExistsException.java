package com.hortinis.sync.protocol;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.NonSerializableClass"})
public final class RecordAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationId operationId;
  private final TechnicalRecord currentRecord;

  public RecordAlreadyExistsException(OperationId operationId, TechnicalRecord currentRecord) {
    super("The record already exists.");
    this.operationId = operationId;
    this.currentRecord = currentRecord;
  }

  public OperationId operationId() {
    return operationId;
  }

  public TechnicalRecord currentRecord() {
    return currentRecord;
  }
}
