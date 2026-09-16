package com.hortinis.sync.protocol;

public record CreateTechnicalRecordOperation(String operationId, String recordId, String value)
    implements TechnicalRecordOperation {

  @Override
  public String kind() {
    return "create";
  }
}
