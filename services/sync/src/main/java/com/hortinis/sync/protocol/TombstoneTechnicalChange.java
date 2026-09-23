package com.hortinis.sync.protocol;

public record TombstoneTechnicalChange(
    String operationId, TechnicalTombstone tombstone, String sequence) implements TechnicalChange {}
