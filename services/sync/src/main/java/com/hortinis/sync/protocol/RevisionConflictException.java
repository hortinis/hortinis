package com.hortinis.sync.protocol;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.NonSerializableClass"})
public final class RevisionConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String operationId;
  private final String expectedRevision;
  private final TechnicalRecord currentRecord;

  public RevisionConflictException(
      String operationId, String expectedRevision, TechnicalRecord currentRecord) {
    super("The expected revision does not match the current revision.");
    this.operationId = operationId;
    this.expectedRevision = expectedRevision;
    this.currentRecord = currentRecord;
  }

  public String operationId() {
    return operationId;
  }

  public String expectedRevision() {
    return expectedRevision;
  }

  public TechnicalRecord currentRecord() {
    return currentRecord;
  }
}
