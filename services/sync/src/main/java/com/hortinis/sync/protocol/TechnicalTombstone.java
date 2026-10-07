package com.hortinis.sync.protocol;

public record TechnicalTombstone(RecordId recordId, Revision revision, String deletedAtSequence) {}
