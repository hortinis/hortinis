package com.hortinis.sync.protocol;

public sealed interface TechnicalRecordOperation
    permits CreateTechnicalRecordOperation,
        ReplaceTechnicalRecordOperation,
        DeleteTechnicalRecordOperation {

  String operationId();

  String recordId();

  String kind();
}
