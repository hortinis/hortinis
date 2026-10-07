package com.hortinis.sync.protocol;

public record TechnicalRecord(RecordId recordId, Revision revision, String value) {}
