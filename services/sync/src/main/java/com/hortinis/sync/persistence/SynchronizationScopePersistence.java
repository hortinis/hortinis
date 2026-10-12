package com.hortinis.sync.persistence;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Installation checks, acceptance locks, and transactional sequence allocation. */
@Repository
@SynchronizationPersistenceEnabled
public class SynchronizationScopePersistence {
  private final JdbcClient jdbc;
  private final CurrentAuthenticationAuthorityPersistence authentication;

  /** Uses the dedicated JDBC adapter for scope state. */
  public SynchronizationScopePersistence(
      JdbcClient jdbc, CurrentAuthenticationAuthorityPersistence authentication) {
    this.jdbc = jdbc;
    this.authentication = authentication;
  }

  /** Denies absent/mismatched context and returns the committed publication counter. */
  public long requireScope(SynchronizationAccessContext context) {
    return readScope(context, false);
  }

  /** Locks current authority, then this scope, through the caller's acceptance transaction. */
  public void lockForAcceptance(SynchronizationAccessContext context) {
    readScope(context, true);
  }

  private long readScope(SynchronizationAccessContext context, boolean lock) {
    if (context == null) {
      throw new SynchronizationAccessDeniedException();
    }
    String mode = "single-user-no-auth";
    if (context.authority()
        instanceof SynchronizationAccessContext.AuthenticatedAuthority authority) {
      mode = authority.mode().wireValue();
      // Reject unsupported/committed-mode mismatch before looking up an account or any scoped row.
      if (!"single-user-auth".equals(mode)
          || !jdbc.sql(
                  "SELECT EXISTS (SELECT 1 FROM technical_server_installation "
                      + "WHERE singleton AND server_instance_id = ? AND access_mode = ?)")
              .params(UUID.fromString(context.serverInstanceId().value()), mode)
              .query(Boolean.class)
              .single()) {
        throw new SynchronizationAccessDeniedException();
      }
      authentication.requireCurrent(
          UUID.fromString(authority.accountId().value()), authority.sessionReference(),
          authority.authorityEpoch(), UUID.fromString(context.serverInstanceId().value()),
          UUID.fromString(context.synchronizationScopeId().value()), mode);
    }
    return jdbc.sql(
            "SELECT s.last_published_sequence FROM technical_synchronization_scope s "
                + "JOIN technical_server_installation i "
                + "ON i.server_instance_id = s.server_instance_id "
                + "WHERE s.synchronization_scope_id = ? AND s.server_instance_id = ? "
                + "AND i.access_mode = ?"
                + (lock ? " FOR UPDATE OF s" : ""))
        .params(
            UUID.fromString(context.synchronizationScopeId().value()),
            UUID.fromString(context.serverInstanceId().value()),
            mode)
        .query(Long.class)
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
  }

  /** Advances the counter in the caller's locked acceptance transaction. */
  public long allocateSequence(SynchronizationAccessContext context) {
    requireScope(context);
    return jdbc.sql(
            "UPDATE technical_synchronization_scope "
                + "SET last_published_sequence = last_published_sequence + 1 "
                + "WHERE synchronization_scope_id = ? AND server_instance_id = ? "
                + "RETURNING last_published_sequence")
        .params(
            UUID.fromString(context.synchronizationScopeId().value()),
            UUID.fromString(context.serverInstanceId().value()))
        .query(Long.class)
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
  }
}
