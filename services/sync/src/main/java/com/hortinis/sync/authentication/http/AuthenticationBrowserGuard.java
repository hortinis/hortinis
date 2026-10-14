package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.BoundWebAuthnOperations;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CompositeHeaderWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exact configured browser origin and fixed first-party headers, before CSRF/proof/body processing.
 */
public final class AuthenticationBrowserGuard extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private static final Set<String> BODYLESS_POSTS =
      Set.of(
          "/api/v1/auth/credentials/options",
          "/api/v1/auth/recovery-codes",
          "/api/v1/auth/activity");
  private static final String SITE = "Sec-Fetch-Site";
  private static final String MODE = "Sec-Fetch-Mode";
  private static final String DEST = "Sec-Fetch-Dest";
  private static final String ORIGIN = "Origin";
  private static final String CSP =
      "default-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; "
          + "font-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; "
          + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'; worker-src 'self'";
  private final String origin;
  private final HeaderWriter headers;

  /** TLS validation is an explicit operator gate; request/forwarded headers cannot enable HSTS. */
  public AuthenticationBrowserGuard(String configuredOrigin, boolean validatedTls) {
    origin = BoundWebAuthnOperations.canonicalPublicOrigin(configuredOrigin);
    headers =
        new CompositeHeaderWriter(
            List.of(
                new ContentSecurityPolicyHeaderWriter(CSP),
                new XContentTypeOptionsHeaderWriter(),
                new ReferrerPolicyHeaderWriter(),
                new XFrameOptionsHeaderWriter(),
                new PermissionsPolicyHeaderWriter(
                    "camera=(), microphone=(), geolocation=(), publickey-credentials-get=(self), "
                        + "publickey-credentials-create=(self)"),
                new HstsHeaderWriter(request -> validatedTls, 31_536_000, false, false)));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    headers.writeHeaders(request, response);
    boolean authentication = request.getRequestURI().startsWith("/api/v1/auth/");
    boolean sensitiveGet = authentication && !"OPTIONS".equals(request.getMethod());
    boolean unsafe = !SAFE.contains(request.getMethod());
    if ((unsafe || sensitiveGet) && !sameOriginFetch(request, unsafe)) {
      AuthenticationHttpErrors.DENIED.write(response);
      return;
    }
    if (authentication && request.getQueryString() != null) {
      AuthenticationHttpErrors.INVALID.write(response);
      return;
    }
    boolean emptyContract =
        "POST".equals(request.getMethod())
            && BODYLESS_POSTS.contains(request.getRequestURI())
            && request.getContentLengthLong() <= 0
            && Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE)).isEmpty();
    if (unsafe && !emptyContract && !jsonContent(request)) {
      AuthenticationHttpErrors.INVALID.write(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean sameOriginFetch(HttpServletRequest request, boolean unsafe) {
    String suppliedOrigin = single(request, ORIGIN);
    String mode = single(request, MODE);
    return "same-origin".equals(single(request, SITE))
        && ("cors".equals(mode) || "same-origin".equals(mode))
        && "empty".equals(single(request, DEST))
        && request.getHeader("Sec-Fetch-User") == null
        && (unsafe
            ? origin.equals(suppliedOrigin)
            : suppliedOrigin == null && Collections.list(request.getHeaders(ORIGIN)).isEmpty()
                || origin.equals(suppliedOrigin));
  }

  private static String single(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    return values.size() == 1 ? values.getFirst() : null;
  }

  private static boolean jsonContent(HttpServletRequest request) {
    String value = single(request, HttpHeaders.CONTENT_TYPE);
    if (value == null) {
      return false;
    }
    try {
      MediaType type = MediaType.parseMediaType(value);
      return MediaType.APPLICATION_JSON.getType().equals(type.getType())
          && MediaType.APPLICATION_JSON.getSubtype().equals(type.getSubtype())
          && (type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset()));
    } catch (InvalidMediaTypeException exception) {
      return false;
    }
  }
}
