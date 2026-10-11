package com.hortinis.sync.http;

import com.hortinis.sync.access.BindingPreconditionFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;

/** No selectors or credentials; ambiguous/missing header provenance fails closed. */
public final class SynchronizationExpectationHeader {
  private SynchronizationExpectationHeader() {}

  /** Requires exactly one bounded expectation, independent of current authority. */
  public static String require(HttpServletRequest request) {
    var values = Collections.list(request.getHeaders("Sync-Expectation"));
    if (values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9_-]{1,256}")) {
      throw new BindingPreconditionFailedException();
    }
    return values.getFirst();
  }
}
