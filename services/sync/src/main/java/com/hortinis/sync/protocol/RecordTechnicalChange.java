package com.hortinis.sync.protocol;

public record RecordTechnicalChange(
    OperationId operationId, TechnicalRecord record, String sequence) implements TechnicalChange {}
