package com.hortinis.sync.protocol;

public record TechnicalChange(String operationId, TechnicalRecord record, String sequence) {}
