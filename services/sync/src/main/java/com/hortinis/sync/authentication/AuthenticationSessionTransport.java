package com.hortinis.sync.authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Request-bound transport entry point; persistent policy alone determines account/data authority.
 */
public interface AuthenticationSessionTransport {
  /** Non-authorizing target stored alongside the native context; never a cookie identifier. */
  String LOGOUT_TARGET_ATTRIBUTE = "hortinis.logout-target";

  /** Reads reference and verifier without allocating a session or exposing its cookie secret. */
  Optional<Observation> observe();

  /** Trusted allocator receives the generated ID only inside the admission transaction. */
  Observation bootstrap(Function<String, SessionAuthorityReference> admission);

  /**
   * Atomically rotates cookie identity, CSRF and reference; the replacement row is caller-owned.
   */
  Observation rotate(SessionAuthorityReference replacement, Duration transportLifetime);

  /** Script-memory CSRF value; reading does not allocate or renew an authentication lifetime. */
  Optional<String> csrfValue();

  /** Explicit transactional replacement; no cookie change or authentication-lifetime renewal. */
  void refreshCsrf();

  /** Publishes completed two-factor authentication through the framework's native context. */
  void authenticate(UUID account, long epoch, Instant verifiedAt, String logoutTarget);

  /** Invalidates native transport inside the caller's authoritative invalidation transaction. */
  void invalidate();

  /**
   * Internal reference/verifier observation; no secret, identity or body diagnostic representation.
   */
  record Observation(SessionAuthorityReference reference, String verifier) {
    @Override
    public String toString() {
      return "SessionTransportObservation[redacted]";
    }
  }
}
