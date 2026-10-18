package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Protected closed event storage; no identity, address, credential, body or arbitrary payload. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSecurityEventPersistence {
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Fresh references and timestamps are generated internally rather than received from clients. */
  public AuthenticationSecurityEventPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Success belongs to publication; denied outcomes require their own committed caller transaction.
   */
  public void append(Event event, Outcome outcome, Reason reason) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Security event storage requires a writable transaction.");
    }
    if (event == null || outcome == null || reason == null || !compatible(outcome, reason)) {
      throw new IllegalArgumentException("Invalid closed security event.");
    }
    jdbc.sql(
            "INSERT INTO authentication_security_event "
                + "(request_reference, occurred_at, event, outcome, reason) VALUES (?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            Timestamp.from(clock.instant()),
            code(event),
            code(outcome),
            code(reason))
        .update();
  }

  private static boolean compatible(Outcome outcome, Reason reason) {
    return switch (outcome) {
      case SUCCEEDED -> reason == Reason.COMPLETED;
      case DENIED -> reason != Reason.COMPLETED && reason != Reason.UNAVAILABLE;
      case UNAVAILABLE -> reason == Reason.UNAVAILABLE;
    };
  }

  private static String code(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Closed authentication actions; no actor or credential reference is part of an event. */
  public enum Event {
    LOGIN,
    LOGOUT
  }

  /** Closed externally meaningful outcomes. */
  public enum Outcome {
    SUCCEEDED,
    DENIED,
    UNAVAILABLE
  }

  /** Closed reasons; provider errors and free text cannot cross this boundary. */
  public enum Reason {
    COMPLETED,
    INVALID_PROOF,
    EXPIRED,
    PRECONDITION,
    CAPACITY,
    UNAVAILABLE
  }
}
