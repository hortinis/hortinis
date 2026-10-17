package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationObservationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal public reads delegate to the native session observation service. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
public final class AuthenticationReadController {
  private final AuthenticationObservationService observation;

  /** No servlet session workflow or authentication publication belongs to this controller. */
  public AuthenticationReadController(AuthenticationObservationService observation) {
    this.observation = observation;
  }

  /** Explicit bounded CSRF bootstrap. */
  @GetMapping("/csrf")
  public ResponseEntity<?> csrf(HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(observation.csrf(request));
  }

  /** Current authority only; no new session and no email/scope/expiry/target fields. */
  @GetMapping("/session")
  public ResponseEntity<?> status(HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(observation.status(request));
  }
}
