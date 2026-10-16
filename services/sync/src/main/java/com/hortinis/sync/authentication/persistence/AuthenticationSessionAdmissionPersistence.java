package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Creates restricted bootstrap data; Spring Session owns its storage and lifecycle. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionAdmissionPersistence {
  private final JdbcClient jdbc;
  private final Clock clock;

  /** Uses the persisted access mode and trusted clock for bootstrap. */
  public AuthenticationSessionAdmissionPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Creates non-authorizing ceremony data inside the publication transaction. */
  public AuthenticationSession admitAnonymous(String frameworkSessionId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session admission requires a publication transaction.");
    }
    if (!AuthenticationSecrets.isRandomValue(frameworkSessionId)) {
      throw new IllegalArgumentException("Invalid framework session ID.");
    }
    if (!jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton")
        .query(String.class)
        .optional()
        .filter("single-user-auth"::equals)
        .isPresent()) {
      throw new AuthenticationFailedException();
    }
    var now = clock.instant();
    return new AuthenticationSession(
        UUID.randomUUID(),
        null,
        0,
        "anonymous",
        now,
        now.plus(AuthenticationLifetime.LIMITED_ABSOLUTE),
        null,
        null,
        null,
        null,
        null,
        AuthenticationSecrets.randomValue(),
        null,
        null);
  }
}
