package com.hortinis.sync.protocol;

public sealed interface TechnicalRecordOperation
    permits CreateTechnicalRecordOperation, ReplaceTechnicalRecordOperation {

  String operationId();

  String recordId();

  String value();

  String kind();
}
