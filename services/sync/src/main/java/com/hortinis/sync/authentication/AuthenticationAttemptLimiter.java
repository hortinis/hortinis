package com.hortinis.sync.authentication;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Process-local bounded rolling windows keyed by independent HMAC, never retained subject text. */
public final class AuthenticationAttemptLimiter {
  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final Duration KEY_IDLE = Duration.ofMinutes(15);
  private static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(60);
  private static final int MAX_KEYS = 4096;
  private final Clock clock;
  private final int maximumKeys;
  private final Mac mac;
  private final Map<String, Attempts> subjects = new HashMap<>();
  private final Attempts installation = new Attempts();
  private Instant lastObserved;
  private Instant lastCleanup;

  /** Uses the trusted clock and an independent per-process key with fixed production capacity. */
  public AuthenticationAttemptLimiter(Clock clock) {
    this(clock, MAX_KEYS);
  }

  AuthenticationAttemptLimiter(Clock clock, int maximumKeys) {
    if (maximumKeys < 1 || maximumKeys > MAX_KEYS) {
      throw new IllegalArgumentException("Invalid authentication admission capacity.");
    }
    this.clock = java.util.Objects.requireNonNull(clock);
    this.maximumKeys = maximumKeys;
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    try {
      mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Required authentication admission primitive is unavailable.");
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }
  }

  /**
   * Source must come from the trusted transport/proxy adapter, never unvalidated forwarding input.
   */
  public synchronized boolean admit(String canonicalLogin, String trustedSource) {
    if (canonicalLogin == null || canonicalLogin.length() > 254) {
      return false;
    }
    return admitSubjects(canonicalLogin, trustedSource);
  }

  /** Proofs without a submitted login share source/installation bounds without a fake login key. */
  public synchronized boolean admitSource(String trustedSource) {
    return admitSubjects(null, trustedSource);
  }

  private boolean admitSubjects(String canonicalLogin, String trustedSource) {
    if (trustedSource == null || trustedSource.length() > 256) {
      return false;
    }
    Instant now = clock.instant();
    if (lastObserved != null && now.isBefore(lastObserved)) {
      return false;
    }
    lastObserved = now;
    cleanupAt(now);
    String loginKey = canonicalLogin == null ? null : key("login:", canonicalLogin);
    String sourceKey = key("source:", trustedSource);
    Attempts login = loginKey == null ? null : subjects.get(loginKey);
    Attempts source = subjects.get(sourceKey);
    if (login != null) {
      login.lastAttempt = now;
    }
    if (source != null) {
      source.lastAttempt = now;
    }
    int additional = (loginKey != null && login == null ? 1 : 0) + (source == null ? 1 : 0);
    if (subjects.size() + additional > maximumKeys) {
      return false;
    }
    if (!installation.available(now, 60)
        || (login != null && !login.available(now, 5))
        || (source != null && !source.available(now, 20))) {
      return false;
    }
    if (loginKey != null) {
      login = subjects.computeIfAbsent(loginKey, unused -> new Attempts());
      login.add(now);
    }
    source = subjects.computeIfAbsent(sourceKey, unused -> new Attempts());
    source.add(now);
    installation.add(now);
    return true;
  }

  /** Also invoked by the scheduled lifecycle adapter; no subject state is exposed by cleanup. */
  public synchronized void cleanup() {
    Instant now = clock.instant();
    if (lastObserved == null || !now.isBefore(lastObserved)) {
      lastObserved = now;
      cleanupAt(now);
    }
  }

  synchronized int retainedKeyCount() {
    return subjects.size();
  }

  private void cleanupAt(Instant now) {
    if (lastCleanup == null || !now.isBefore(lastCleanup.plus(CLEANUP_INTERVAL))) {
      subjects
          .entrySet()
          .removeIf(entry -> !now.isBefore(entry.getValue().lastAttempt.plus(KEY_IDLE)));
      installation.expireWindow(now);
      lastCleanup = now;
    }
  }

  private String key(String purpose, String value) {
    return HexFormat.of()
        .formatHex(mac.doFinal((purpose + value).getBytes(StandardCharsets.UTF_8)));
  }

  private static final class Attempts {
    private final ArrayDeque<Instant> accepted = new ArrayDeque<>();
    private Instant lastAttempt;

    private boolean available(Instant now, int maximum) {
      expireWindow(now);
      return accepted.size() < maximum;
    }

    private void expireWindow(Instant now) {
      while (!accepted.isEmpty() && !now.isBefore(accepted.getFirst().plus(WINDOW))) {
        accepted.removeFirst();
      }
    }

    private void add(Instant now) {
      accepted.addLast(now);
      lastAttempt = now;
    }
  }
}
