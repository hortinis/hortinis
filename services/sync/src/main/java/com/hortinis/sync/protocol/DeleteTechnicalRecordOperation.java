package com.hortinis.sync.protocol;

public record DeleteTechnicalRecordOperation(
    String operationId, String recordId, String expectedRevision)
    implements TechnicalRecordOperation {

  @Override
  public String kind() {
    return "delete";
  }
}
