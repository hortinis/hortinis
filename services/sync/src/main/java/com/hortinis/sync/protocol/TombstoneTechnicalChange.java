package com.hortinis.sync.protocol;

public record TombstoneTechnicalChange(
    OperationId operationId, TechnicalTombstone tombstone, String sequence)
    implements TechnicalChange {}
