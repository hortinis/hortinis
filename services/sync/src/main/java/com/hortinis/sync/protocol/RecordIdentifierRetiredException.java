package com.hortinis.sync.protocol;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class RecordIdentifierRetiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String operationId;
  private final String recordId;

  public RecordIdentifierRetiredException(String operationId, String recordId) {
    super("The record identifier was retired by an accepted deletion.");
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
