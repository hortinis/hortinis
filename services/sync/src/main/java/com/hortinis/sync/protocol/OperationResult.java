package com.hortinis.sync.protocol;

public sealed interface OperationResult permits RecordOperationResult, TombstoneOperationResult {

  String outcome();

  OperationId operationId();

  String sequence();
}
