package com.hortinis.sync.protocol;

public record ReplaceTechnicalRecordOperation(
    String operationId, String recordId, String value, String expectedRevision)
    implements TechnicalRecordOperation {

  @Override
  public String kind() {
    return "replace";
  }
}
