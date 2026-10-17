package com.hortinis.sync.authentication.http;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Syntax guards use the same decoded servlet-path matching as Spring's native login/logout. */
final class AuthenticationRoutes {
  static final RequestMatcher ALL =
      PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");
  static final RequestMatcher LOGIN =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login");
  static final RequestMatcher LOGOUT =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout");

  private AuthenticationRoutes() {}
}
