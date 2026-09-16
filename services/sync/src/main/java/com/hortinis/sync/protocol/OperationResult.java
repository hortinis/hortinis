package com.hortinis.sync.protocol;

public record OperationResult(
    String outcome, String operationId, TechnicalRecord record, String sequence) {}
