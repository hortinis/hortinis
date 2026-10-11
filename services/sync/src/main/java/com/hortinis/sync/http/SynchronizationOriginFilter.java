package com.hortinis.sync.http;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects browser cross-origin synchronization mutations; never trusts forwarded origin claims. */
@Component
@SynchronizationPersistenceEnabled
public class SynchronizationOriginFilter extends OncePerRequestFilter {
  private final URI publicOrigin;

  /** Requires an operator-selected browser origin; Host/forwarded headers cannot grant trust. */
  public SynchronizationOriginFilter(Environment environment) {
    publicOrigin = parseOrigin(environment.getProperty("hortinis.access.public-origin"));
    if (publicOrigin == null) {
      throw new IllegalStateException("An explicit valid public browser origin is required.");
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return "GET".equals(request.getMethod())
        || "HEAD".equals(request.getMethod())
        || "OPTIONS".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
    var sites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
    if (origins.size() > 1
        || sites.size() > 1
        || (!sites.isEmpty()
            && !"same-origin".equals(sites.getFirst())
            && !"none".equals(sites.getFirst()))
        || (!origins.isEmpty() && !sameOrigin(origins.getFirst()))) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access is denied.\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean sameOrigin(String value) {
    URI origin = parseOrigin(value);
    return origin != null
        && publicOrigin.getScheme().equalsIgnoreCase(origin.getScheme())
        && publicOrigin.getHost().equalsIgnoreCase(origin.getHost())
        && effectivePort(publicOrigin) == effectivePort(origin);
  }

  private static int effectivePort(URI origin) {
    return origin.getPort() == -1
        ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80)
        : origin.getPort();
  }

  private static URI parseOrigin(String value) {
    if (value == null) {
      return null;
    }
    try {
      URI origin = new URI(value);
      boolean valid =
          ("http".equalsIgnoreCase(origin.getScheme())
                  || "https".equalsIgnoreCase(origin.getScheme()))
              && origin.getHost() != null
              && effectivePort(origin) > 0
              && effectivePort(origin) <= 65_535
              && origin.getRawUserInfo() == null
              && "".equals(origin.getRawPath())
              && origin.getRawQuery() == null
              && origin.getRawFragment() == null
              && !origin.getRawAuthority().endsWith(":");
      return valid ? origin : null;
    } catch (URISyntaxException exception) {
      return null;
    }
  }
}
