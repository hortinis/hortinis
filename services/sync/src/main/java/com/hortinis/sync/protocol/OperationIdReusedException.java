package com.hortinis.sync.protocol;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.NonSerializableClass"})
public final class OperationIdReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationId operationId;

  public OperationIdReusedException(OperationId operationId) {
    super("The operation identifier was reused.");
    this.operationId = operationId;
  }

  public OperationId operationId() {
    return operationId;
  }
}
