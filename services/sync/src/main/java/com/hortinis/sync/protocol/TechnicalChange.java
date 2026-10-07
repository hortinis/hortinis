package com.hortinis.sync.protocol;

public sealed interface TechnicalChange permits RecordTechnicalChange, TombstoneTechnicalChange {

  OperationId operationId();

  String sequence();
}
