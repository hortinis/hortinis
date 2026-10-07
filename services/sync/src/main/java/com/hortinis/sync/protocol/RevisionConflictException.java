package com.hortinis.sync.protocol;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.NonSerializableClass"})
public final class RevisionConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationId operationId;
  private final ExpectedRevision expectedRevision;
  private final TechnicalRecord currentRecord;

  public RevisionConflictException(
      OperationId operationId, ExpectedRevision expectedRevision, TechnicalRecord currentRecord) {
    super("The expected revision does not match the current revision.");
    this.operationId = operationId;
    this.expectedRevision = expectedRevision;
    this.currentRecord = currentRecord;
  }

  public OperationId operationId() {
    return operationId;
  }

  public ExpectedRevision expectedRevision() {
    return expectedRevision;
  }

  public TechnicalRecord currentRecord() {
    return currentRecord;
  }
}
