package com.hortinis.sync.persistence;

import com.hortinis.sync.access.BindingPreconditionFailedException;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessUnavailableException;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Scope-owned, bounded observations checked only after current authority resolution. */
@Repository
@SynchronizationPersistenceEnabled
public class SynchronizationExpectationPersistence {
  private static final int MAX_OUTSTANDING = 128;
  private final JdbcClient jdbc;
  private final SynchronizationScopePersistence scopes;

  /** Uses scoped JDBC access without a replaceable authorization shortcut. */
  public SynchronizationExpectationPersistence(
      JdbcClient jdbc, SynchronizationScopePersistence scopes) {
    this.jdbc = jdbc;
    this.scopes = scopes;
  }

  /** Includes acceptance evidence and reservations, not just the visible live projection. */
  public boolean isEmpty(SynchronizationAccessContext context) {
    long published = scopes.requireScope(context);
    if (published != 0) {
      return false;
    }
    return jdbc.sql(
            "SELECT NOT ("
                + "EXISTS (SELECT 1 FROM technical_record WHERE "
                + "synchronization_scope_id = :scope) OR "
                + "EXISTS (SELECT 1 FROM accepted_technical_record_operation "
                + "WHERE synchronization_scope_id = :scope) OR "
                + "EXISTS (SELECT 1 FROM technical_record_change WHERE "
                + "synchronization_scope_id = :scope) OR "
                + "EXISTS (SELECT 1 FROM technical_record_tombstone WHERE "
                + "synchronization_scope_id = :scope) OR "
                + "EXISTS (SELECT 1 FROM retired_technical_record_identifier "
                + "WHERE synchronization_scope_id = :scope))")
        .param("scope", scope(context))
        .query(Boolean.class)
        .single();
  }

  /** Caller holds the publication lock; expiry never moves when the observation is reused. */
  public String issue(SynchronizationAccessContext context, boolean empty) {
    scopes.requireScope(context);
    jdbc.sql(
            "DELETE FROM technical_exchange_expectation WHERE "
                + "synchronization_scope_id = ? AND expires_at <= "
                + "clock_timestamp()")
        .param(scope(context))
        .update();
    int outstanding =
        jdbc.sql(
                "SELECT count(*) FROM technical_exchange_expectation WHERE "
                    + "synchronization_scope_id = ?")
            .param(scope(context))
            .query(Integer.class)
            .single();
    if (outstanding >= MAX_OUTSTANDING) {
      throw new SynchronizationAccessUnavailableException();
    }
    String expectation = UUID.randomUUID().toString();
    jdbc.sql(
            "INSERT INTO technical_exchange_expectation "
                + "(synchronization_scope_id, expectation, server_instance_id, "
                + "access_mode, account_id, phase, expires_at) "
                + "VALUES (:scope, :expectation, :instance, :mode, :account, "
                + ":phase, clock_timestamp() + INTERVAL '15 minutes')")
        .param("scope", scope(context))
        .param("expectation", expectation)
        .param("instance", UUID.fromString(context.serverInstanceId().value()))
        .param("mode", mode(context))
        .param("account", account(context), Types.OTHER)
        .param("phase", empty ? "observed" : "established")
        .update();
    return expectation;
  }

  /** Generic failure for absent, expired, foreign, or mismatched observations. */
  public String require(SynchronizationAccessContext context, String expectation) {
    scopes.requireScope(context);
    if (expectation == null || !expectation.matches("[A-Za-z0-9_-]{1,256}")) {
      throw new BindingPreconditionFailedException();
    }
    var row =
        jdbc.sql(
                "SELECT access_mode, account_id::text, phase FROM technical_exchange_expectation "
                    + "WHERE synchronization_scope_id = ? AND server_instance_id = "
                    + "? AND expectation = ? "
                    + "AND expires_at > clock_timestamp() AND boundary_kind = 'generationless'")
            .params(
                scope(context), UUID.fromString(context.serverInstanceId().value()), expectation)
            .query(
                (result, number) ->
                    new Observation(result.getString(1), result.getString(2), result.getString(3)))
            .optional()
            .orElseThrow(BindingPreconditionFailedException::new);
    if (!row.mode().equals(mode(context))
        || !java.util.Objects.equals(row.account(), account(context))) {
      throw new BindingPreconditionFailedException();
    }
    return row.phase();
  }

  /** Caller holds the scope publication lock throughout the containing transaction. */
  public void phase(SynchronizationAccessContext context, String expectation, String phase) {
    require(context, expectation);
    jdbc.sql(
            "UPDATE technical_exchange_expectation SET phase = ? WHERE "
                + "synchronization_scope_id = ? AND expectation = ?")
        .params(phase, scope(context), expectation)
        .update();
  }

  /** Maintenance deletes expired protocol metadata only, without reading user content. */
  public void purgeExpired() {
    jdbc.sql("DELETE FROM technical_exchange_expectation WHERE expires_at <= clock_timestamp()")
        .update();
  }

  private static UUID scope(SynchronizationAccessContext context) {
    return UUID.fromString(context.synchronizationScopeId().value());
  }

  private static String mode(SynchronizationAccessContext context) {
    return context.authority()
            instanceof SynchronizationAccessContext.AuthenticatedAuthority authority
        ? authority.mode().wireValue()
        : ServerAccessMode.SINGLE_USER_NO_AUTH.wireValue();
  }

  private static String account(SynchronizationAccessContext context) {
    return context.authority()
            instanceof SynchronizationAccessContext.AuthenticatedAuthority authority
        ? authority.accountId().value()
        : null;
  }

  private record Observation(String mode, String account, String phase) {
    @Override
    public String toString() {
      return "Observation[redacted]";
    }
  }
}
