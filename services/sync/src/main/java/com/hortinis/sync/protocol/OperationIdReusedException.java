package com.hortinis.sync.protocol;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class OperationIdReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String operationId;

  public OperationIdReusedException(String operationId) {
    super("The operation identifier was reused.");
    this.operationId = operationId;
  }

  public String operationId() {
    return operationId;
  }
}
