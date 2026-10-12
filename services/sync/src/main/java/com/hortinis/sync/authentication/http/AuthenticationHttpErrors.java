package com.hortinis.sync.authentication.http;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Fixed closed transport failures; supplied input and provider causes cannot become responses. */
public enum AuthenticationHttpErrors {
  INVALID(400, "INVALID_REQUEST", "The request is invalid."),
  FAILED(401, "AUTHENTICATION_FAILED", "Authentication failed."),
  REQUIRED(401, "AUTHENTICATION_REQUIRED", "Authentication is required."),
  DENIED(403, "ACCESS_DENIED", "Access is denied."),
  PRECONDITION(
      409,
      "AUTHENTICATION_PRECONDITION_FAILED",
      "The authentication precondition no longer holds."),
  THROTTLED(429, "AUTHENTICATION_THROTTLED", "Authentication attempts are temporarily limited."),
  UNAVAILABLE(503, "ACCESS_UNAVAILABLE", "The access service is unavailable.");

  private final int status;
  private final String code;
  private final String message;

  AuthenticationHttpErrors(int status, String code, String message) {
    this.status = status;
    this.code = code;
    this.message = message;
  }

  /** Writes no cookie, secret, exception message or redirect. */
  public void write(HttpServletResponse response) throws IOException {
    response.setStatus(status);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    if (this == THROTTLED) {
      response.setHeader(HttpHeaders.RETRY_AFTER, "60");
    }
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }

  /** Equivalent closed MVC response for feature-controller failures. */
  public ResponseEntity<Map<String, String>> response() {
    var builder = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (this == THROTTLED) {
      builder.header(HttpHeaders.RETRY_AFTER, "60");
    }
    return builder.body(Map.of("code", code, "message", message));
  }
}
