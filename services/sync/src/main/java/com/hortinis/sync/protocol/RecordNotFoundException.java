package com.hortinis.sync.protocol;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class RecordNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String operationId;
  private final String recordId;

  public RecordNotFoundException(String operationId, String recordId) {
    super("The record does not exist.");
    this.operationId = operationId;
    this.recordId = recordId;
  }

  public String operationId() {
    return operationId;
  }

  public String recordId() {
    return recordId;
  }
}
