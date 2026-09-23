package com.hortinis.sync.protocol;

public record TechnicalTombstone(String recordId, String revision, String deletedAtSequence) {}
