package com.hortinis.sync.protocol;

public record TombstoneOperationResult(
    String outcome, String operationId, TechnicalTombstone tombstone, String sequence)
    implements OperationResult {}
