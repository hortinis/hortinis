package com.hortinis.sync.protocol;

public sealed interface TechnicalRecordOperation
    permits CreateTechnicalRecordOperation,
        ReplaceTechnicalRecordOperation,
        DeleteTechnicalRecordOperation {

  OperationId operationId();

  RecordId recordId();

  String kind();
}
