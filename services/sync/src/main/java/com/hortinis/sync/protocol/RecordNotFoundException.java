package com.hortinis.sync.protocol;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.NonSerializableClass"})
public final class RecordNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationId operationId;
  private final RecordId recordId;

  public RecordNotFoundException(OperationId operationId, RecordId recordId) {
    super("The record does not exist.");
    this.operationId = operationId;
    this.recordId = recordId;
  }

  public OperationId operationId() {
    return operationId;
  }

  public RecordId recordId() {
    return recordId;
  }
}
