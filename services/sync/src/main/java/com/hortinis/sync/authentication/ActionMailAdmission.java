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
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Provisional process-local mail budgets; Q1/Q2 remain required R6 decisions. */
public final class ActionMailAdmission {
  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration DAY = Duration.ofDays(1);
  private final Clock clock;
  private final AuthenticationAdmissionCapacity capacity;
  private final Mac mac;
  private final Map<String, Window> emails = new HashMap<>();
  private final Window installation = new Window();
  private final Semaphore deliveries = new Semaphore(2);
  private Instant lastObserved;

  /**
   * Fixed production bound; process-local accounting cannot enforce quotas across host processes.
   */
  public ActionMailAdmission(Clock clock) {
    this(clock, 4096);
  }

  ActionMailAdmission(Clock clock, int maximumKeys) {
    this(clock, new AuthenticationAdmissionCapacity(maximumKeys));
  }

  ActionMailAdmission(Clock clock, AuthenticationAdmissionCapacity capacity) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.capacity = java.util.Objects.requireNonNull(capacity);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    try {
      mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Required mail admission primitive is unavailable.");
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }
  }

  /** Reserves capacity and charges before account lookup; callers must close every permit. */
  public synchronized Permit reserve(String email) {
    final String canonical = EmailAddress.canonical(email);
    Instant now = clock.instant();
    if (lastObserved != null && now.isBefore(lastObserved)) {
      throw new AuthenticationOverloadedException();
    }
    lastObserved = now;
    cleanupAt(now);
    String key =
        HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.US_ASCII)));
    Window window = emails.get(key);
    if (!installation.available(now, 30, 100) || !deliveries.tryAcquire()) {
      throw new AuthenticationOverloadedException();
    }
    if (window == null && !capacity.acquire(1)) {
      deliveries.release();
      throw new AuthenticationOverloadedException();
    }
    window = emails.computeIfAbsent(key, unused -> new Window());
    window.lastUsed = now;
    if (!window.available(now, 3, 10)
        || (!window.attempts.isEmpty()
            && now.isBefore(window.attempts.getLast().plusSeconds(60)))) {
      deliveries.release();
      return new Permit(false);
    }
    window.attempts.addLast(now);
    installation.attempts.addLast(now);
    return new Permit(true);
  }

  /** Scheduled every minute; denied active restrictions are retained rather than evicted. */
  public synchronized void cleanup() {
    Instant now = clock.instant();
    if (lastObserved == null || !now.isBefore(lastObserved)) {
      lastObserved = now;
      cleanupAt(now);
    }
  }

  private void cleanupAt(Instant now) {
    emails
        .entrySet()
        .removeIf(
            entry -> {
              if (!now.isBefore(entry.getValue().lastUsed.plus(DAY))) {
                capacity.release();
                return true;
              }
              return false;
            });
    installation.expire(now);
  }

  /** One synchronous attempt, with no queue; closing twice cannot enlarge capacity. */
  public final class Permit implements AutoCloseable {
    private final boolean admitted;
    private boolean closed;

    private Permit(boolean allowed) {
      this.admitted = allowed;
    }

    /**
     * False means per-email quota denial, to be generically acknowledged by a future reset route.
     */
    public boolean allowed() {
      return admitted;
    }

    @Override
    public synchronized void close() {
      if (!closed && admitted) {
        deliveries.release();
      }
      closed = true;
    }

    @Override
    public String toString() {
      return "MailPermit[redacted]";
    }
  }

  private static final class Window {
    private final ArrayDeque<Instant> attempts = new ArrayDeque<>();
    private Instant lastUsed;

    private boolean available(Instant now, int hourly, int daily) {
      expire(now);
      return attempts.size() < daily
          && attempts.stream().filter(time -> now.isBefore(time.plus(HOUR))).count() < hourly;
    }

    private void expire(Instant now) {
      while (!attempts.isEmpty() && !now.isBefore(attempts.getFirst().plus(DAY))) {
        attempts.removeFirst();
      }
    }
  }
}
