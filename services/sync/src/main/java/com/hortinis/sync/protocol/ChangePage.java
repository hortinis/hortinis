package com.hortinis.sync.protocol;

import java.util.List;

public record ChangePage(List<TechnicalChange> changes, String nextCursor, boolean hasMore) {}
