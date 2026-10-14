package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationAccountService;
import com.hortinis.sync.authentication.AuthenticationObservationService;
import com.hortinis.sync.authentication.AuthenticationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-renewing authentication reads; protected lists delegate current-account authority. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
public final class AuthenticationReadController {
  private final AuthenticationObservationService observation;
  private final AuthenticationAccountService credentialService;
  private final AuthenticationSessionService sessionService;

  /** Request-bound servlet wrappers are supplied by the selected established session filter. */
  public AuthenticationReadController(
      AuthenticationObservationService observation,
      AuthenticationAccountService credentials,
      AuthenticationSessionService sessions) {
    this.observation = observation;
    this.credentialService = credentials;
    this.sessionService = sessions;
  }

  /** Explicit bounded anonymous bootstrap; existing sessions retain their current lifetime. */
  @GetMapping("/csrf")
  public ResponseEntity<?> csrf(HttpServletRequest request, HttpServletResponse response) {
    return success(observation.csrf(new RequestAuthenticationSession(request, response)));
  }

  /** Status allocates no session and discloses no account metadata before full authentication. */
  @GetMapping("/session")
  public ResponseEntity<?> status(HttpServletRequest request, HttpServletResponse response) {
    return success(observation.status(new RequestAuthenticationSession(request, response)));
  }

  /** Only current owned identifiers/labels, including authorized restricted replacement choices. */
  @GetMapping("/credentials")
  public ResponseEntity<?> credentials(HttpServletRequest request, HttpServletResponse response) {
    return success(
        AuthenticationResponseBody.credentials(
            credentialService.list(new RequestAuthenticationSession(request, response))));
  }

  /** Non-authorizing targets and necessary lifecycle times for the current full account only. */
  @GetMapping("/sessions")
  public ResponseEntity<?> sessions(HttpServletRequest request, HttpServletResponse response) {
    return success(
        Map.of(
            "sessions", sessionService.list(new RequestAuthenticationSession(request, response))));
  }

  /**
   * Only the remaining count, common expiry and trusted-clock warning; no code/verifier material.
   */
  @GetMapping("/recovery-codes")
  public ResponseEntity<?> recoveryCodeStatus(
      HttpServletRequest request, HttpServletResponse response) {
    var status =
        credentialService.recoveryCodeStatus(new RequestAuthenticationSession(request, response));
    Map<String, Object> body =
        new java.util.LinkedHashMap<>(
            Map.of("remaining", status.remaining(), "expiresSoon", status.expiresSoon()));
    if (status.expiresAt() != null) {
      body.put("expiresAt", status.expiresAt().toString());
    }
    return success(body);
  }

  private static ResponseEntity<?> success(Object body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
  }
}
