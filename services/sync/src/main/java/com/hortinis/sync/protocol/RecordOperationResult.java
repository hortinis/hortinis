package com.hortinis.sync.protocol;

public record RecordOperationResult(
    String outcome, String operationId, TechnicalRecord record, String sequence)
    implements OperationResult {}
