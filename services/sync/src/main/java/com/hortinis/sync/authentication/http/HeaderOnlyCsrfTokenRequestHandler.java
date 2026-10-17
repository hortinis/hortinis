package com.hortinis.sync.authentication.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/** One header only, with native BREACH masking/validation and no parameter fallback. */
public final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
  private final XorCsrfTokenRequestAttributeHandler delegate =
      new XorCsrfTokenRequestAttributeHandler();

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
    delegate.handle(request, response, csrfToken);
  }

  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
    var values = Collections.list(request.getHeaders("X-CSRF-TOKEN"));
    return values.size() == 1 ? delegate.resolveCsrfTokenValue(request, token) : null;
  }
}
