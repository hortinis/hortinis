package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded explicit CSRF-bootstrap admission; no admitted row is an authenticated identity. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionAdmissionPersistence {
  private static final int MAX_LIMITED_SESSIONS = 1000;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final ConversionService attributes = SessionAttributeCodec.conversionService();

  /**
   * The trusted framework session generator supplies IDs; no request supplies a secret or scope.
   */
  public AuthenticationSessionAdmissionPersistence(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Allocates one five-minute anonymous authority within the framework cookie creation workflow.
   */
  public SessionAuthorityReference admitAnonymous(String frameworkSessionId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session admission requires a publication transaction.");
    }
    if (!AuthenticationSecrets.isRandomValue(frameworkSessionId)) {
      throw new IllegalArgumentException("Invalid framework session ID.");
    }
    String mode =
        jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton FOR UPDATE")
            .query(String.class)
            .optional()
            .orElseThrow(AuthenticationFailedException::new);
    if (!"single-user-auth".equals(mode)) {
      throw new AuthenticationFailedException();
    }
    Instant now = clock.instant();
    purgeExpiredAnonymous(now);
    int limited =
        jdbc.sql(
                "SELECT count(*) FROM authentication_session_authority "
                    + "WHERE state <> 'full' AND expires_at > ?")
            .param(Timestamp.from(now))
            .query(Integer.class)
            .single();
    if (limited >= MAX_LIMITED_SESSIONS) {
      throw new AuthenticationOverloadedException();
    }
    UUID reference =
        jdbc.sql(
                "INSERT INTO authentication_session_authority "
                    + "(session_verifier, logout_target, state, created_at, expires_at) "
                    + "VALUES (?, ?, 'anonymous', ?, ?) RETURNING session_reference")
            .params(
                AuthenticationSecrets.digest(frameworkSessionId),
                AuthenticationSecrets.randomValue(),
                Timestamp.from(now),
                Timestamp.from(now.plus(AuthenticationLifetime.LIMITED_ABSOLUTE)))
            .query(UUID.class)
            .single();
    return new SessionAuthorityReference(reference);
  }

  private void purgeExpiredAnonymous(Instant now) {
    // These unowned rows require no account lock; installation lock serializes admission/cleanup.
    List<UUID> expired =
        jdbc.sql(
                "SELECT session_reference FROM authentication_session_authority "
                    + "WHERE state = 'anonymous' AND account_id IS NULL AND expires_at <= ? "
                    + "ORDER BY session_reference FOR UPDATE")
            .param(Timestamp.from(now))
            .query(UUID.class)
            .list();
    for (UUID reference : expired) {
      byte[] encoded = attributes.convert(new SessionAuthorityReference(reference), byte[].class);
      jdbc.sql(
              "DELETE FROM spring_session WHERE primary_id IN "
                  + "(SELECT session_primary_id FROM spring_session_attributes "
                  + "WHERE attribute_bytes = ?)")
          .param(encoded)
          .update();
      jdbc.sql("DELETE FROM authentication_session_authority WHERE session_reference = ?")
          .param(reference)
          .update();
    }
  }
}
