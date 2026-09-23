package com.hortinis.sync.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface TechnicalChange permits RecordTechnicalChange, TombstoneTechnicalChange {

  String operationId();

  String sequence();
}
