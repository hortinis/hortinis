package com.hortinis.sync.protocol;

public record TombstoneOperationResult(
    String outcome, OperationId operationId, TechnicalTombstone tombstone, String sequence)
    implements OperationResult {}
