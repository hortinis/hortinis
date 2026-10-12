package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Established request attributes with one plain header only; form/query parameters never qualify.
 */
public final class HeaderOnlyCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
    var values = Collections.list(request.getHeaders("X-CSRF-TOKEN"));
    return values.size() == 1 && AuthenticationSecrets.isRandomValue(values.getFirst())
        ? values.getFirst()
        : null;
  }
}
