package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native row lookup and indexed revocation, without a parallel session-authority registry. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationNativeSessions {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final FindByIndexNameSessionRepository<? extends Session> repository;

  /** Uses Boot's repository and shared datasource/transaction manager. */
  public AuthenticationNativeSessions(
      @Lazy FindByIndexNameSessionRepository<? extends Session> repository,
      Clock clock,
      JdbcClient jdbc) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.repository = repository;
  }

  /** The cookie selects only its native row; identity is compared to its saved context. */
  public Optional<UUID> find(String identifier, AuthenticationIdentity identity) {
    return read("s.session_id = ?", identifier, identity, false);
  }

  /** Caller locks the account before the native session and keeps both through content work. */
  public void requireCurrent(UUID reference, AuthenticationIdentity identity) {
    requireTransaction();
    read("s.primary_id = ?", reference.toString(), identity, true)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /** Every indexed account session is deleted through the native repository in this transaction. */
  public void removeAll(UUID account) {
    requireTransaction();
    // Query the native index, including expired rows awaiting cleanup, in deterministic lock order.
    var identifiers =
        jdbc.sql(
                "SELECT session_id FROM spring_session WHERE principal_name = ? "
                    + "ORDER BY primary_id FOR UPDATE")
            .param(account.toString())
            .query(String.class)
            .list();
    identifiers.forEach(repository::deleteById);
  }

  private Optional<UUID> read(
      String predicate, String selector, AuthenticationIdentity identity, boolean lock) {
    return jdbc.sql(
            "SELECT s.primary_id, a.attribute_bytes FROM spring_session s "
                + "JOIN spring_session_attributes a ON a.session_primary_id = s.primary_id "
                + "AND a.attribute_name = 'SPRING_SECURITY_CONTEXT' WHERE "
                + predicate
                + " AND s.principal_name = ? AND s.expiry_time > ?"
                + (lock ? " FOR SHARE OF s" : ""))
        .params(selector, identity.account().toString(), clock.millis())
        .query(
            (row, number) -> {
              var context =
                  (SecurityContext)
                      SessionAttributeCodec.conversionService()
                          .convert(row.getBytes(2), Object.class);
              return identity.equals(AuthenticationIdentity.from(context.getAuthentication()))
                  ? UUID.fromString(row.getString(1))
                  : null;
            })
        .optional();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session authority requires a writable transaction.");
    }
  }
}
