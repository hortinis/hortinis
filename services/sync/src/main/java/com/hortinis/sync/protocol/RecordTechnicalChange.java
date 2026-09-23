package com.hortinis.sync.protocol;

public record RecordTechnicalChange(String operationId, TechnicalRecord record, String sequence)
    implements TechnicalChange {}
