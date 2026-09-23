package com.hortinis.sync.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface OperationResult permits RecordOperationResult, TombstoneOperationResult {

  String outcome();

  String operationId();

  String sequence();
}
