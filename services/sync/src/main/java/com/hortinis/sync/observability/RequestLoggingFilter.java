package com.hortinis.sync.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Emits a minimal, privacy-safe operational event for each HTTP request. */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    String traceId = UUID.randomUUID().toString();
    long startedAt = System.nanoTime();
    MDC.put("request_id", requestId);
    MDC.put("trace_id", traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      try {
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOGGER
            .atInfo()
            .addKeyValue("event", "request_completed")
            .addKeyValue("method", request.getMethod())
            .addKeyValue("route", routeTemplate(request))
            .addKeyValue("status", response.getStatus())
            .addKeyValue("duration_ms", durationMillis)
            .log("Request completed");
      } finally {
        MDC.remove("request_id");
        MDC.remove("trace_id");
      }
    }
  }

  private static String routeTemplate(HttpServletRequest request) {
    String path = request.getRequestURI();
    return switch (path) {
      case "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" -> path;
      default -> "/unknown";
    };
  }
}
