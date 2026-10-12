package com.hortinis.sync.persistence;

import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads the explicit sole technical scope provisioned by the fresh-install migration. */
@Repository
@SynchronizationPersistenceEnabled
public class ServerInstallationPersistence {
  private static final int SINGLE_INSTALLATION = 1;
  private final JdbcClient jdbc;

  /** Reads installation metadata through JDBC. */
  public ServerInstallationPersistence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Records initial explicit configuration atomically; no direct mode transition is permitted. */
  @Transactional
  public void initializeMode(ServerAccessMode mode) {
    if (mode == ServerAccessMode.MULTI_USER_AUTH) {
      throw new IllegalStateException("Authenticated server access is not implemented.");
    }
    jdbc.sql("SELECT singleton FROM technical_server_installation WHERE singleton FOR UPDATE")
        .query(Boolean.class)
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
    int changed;
    if (mode == ServerAccessMode.SINGLE_USER_AUTH) {
      changed = initializeFreshAuthenticationMode(mode);
    } else {
      changed =
          jdbc.sql(
                  "UPDATE technical_server_installation SET access_mode = ? "
                      + "WHERE singleton AND (access_mode IS NULL OR access_mode = ?)")
              .params(mode.wireValue(), mode.wireValue())
              .update();
    }
    if (changed != SINGLE_INSTALLATION) {
      throw new IllegalStateException("The configured access mode differs from committed setup.");
    }
    if (mode == ServerAccessMode.SINGLE_USER_NO_AUTH) {
      technicalIdentity();
    }
  }

  private int initializeFreshAuthenticationMode(ServerAccessMode mode) {
    String committedMode =
        jdbc.sql("SELECT access_mode FROM technical_server_installation WHERE singleton")
            .query(String.class)
            .optional()
            .orElse(null);
    boolean soleDefaultScope =
        jdbc.sql(
                "SELECT (SELECT count(*) FROM technical_synchronization_scope) = 1 "
                    + "AND EXISTS (SELECT 1 FROM technical_synchronization_scope "
                    + "WHERE technical_default)")
            .query(Boolean.class)
            .single();
    boolean validOwner =
        jdbc.sql(
                "SELECT (SELECT count(*) FROM authentication_account) = "
                    + "(SELECT count(*) FROM authentication_account a "
                    + "JOIN technical_synchronization_scope s "
                    + "ON s.synchronization_scope_id = a.synchronization_scope_id "
                    + "WHERE s.technical_default AND a.status = 'active') "
                    + "AND (SELECT count(*) FROM authentication_account) <= 1")
            .query(Boolean.class)
            .single();
    int accounts =
        jdbc.sql("SELECT count(*) FROM authentication_account").query(Integer.class).single();
    if (!soleDefaultScope || !validOwner) {
      throw new IllegalStateException("Authenticated access requires one owned default scope.");
    }
    if (mode.wireValue().equals(committedMode)) {
      if (accounts == 0 && !technicalDataIsEmpty()) {
        throw new IllegalStateException(
            "Authenticated access has no account for its populated default scope.");
      }
      return 1;
    }
    boolean fresh = committedMode == null && accounts == 0 && technicalDataIsEmpty();
    if (!fresh) {
      throw new IllegalStateException(
          "Authenticated access requires a fresh, empty sole-scope installation.");
    }
    return jdbc.sql(
            "UPDATE technical_server_installation SET access_mode = ? "
                + "WHERE singleton AND access_mode IS NULL")
        .param(mode.wireValue())
        .update();
  }

  private boolean technicalDataIsEmpty() {
    return jdbc.sql(
            "SELECT NOT EXISTS (SELECT 1 FROM technical_record) "
                + "AND NOT EXISTS (SELECT 1 FROM accepted_technical_record_operation) "
                + "AND NOT EXISTS (SELECT 1 FROM technical_record_change) "
                + "AND NOT EXISTS (SELECT 1 FROM technical_record_tombstone) "
                + "AND NOT EXISTS (SELECT 1 FROM retired_technical_record_identifier) "
                + "AND NOT EXISTS (SELECT 1 FROM technical_exchange_expectation) "
                + "AND EXISTS (SELECT 1 FROM technical_synchronization_scope "
                + "WHERE technical_default AND last_published_sequence = 0)")
        .query(Boolean.class)
        .single();
  }

  /** Denies a missing explicitly designated scope instead of selecting any available scope. */
  public TechnicalIdentity technicalIdentity() {
    return jdbc.sql(
            "SELECT i.server_instance_id::text, s.synchronization_scope_id::text "
                + "FROM technical_server_installation i JOIN technical_synchronization_scope s "
                + "ON s.server_instance_id = i.server_instance_id "
                + "WHERE i.singleton AND s.technical_default "
                + "AND i.access_mode = 'single-user-no-auth'")
        .query(
            (row, number) ->
                new TechnicalIdentity(
                    new ServerInstanceId(row.getString(1)),
                    new SynchronizationScopeId(row.getString(2))))
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
  }

  /** Public non-content capabilities read committed configuration, without selecting a scope. */
  public PublicIdentity publicIdentity() {
    return jdbc.sql(
            "SELECT server_instance_id::text, access_mode FROM "
                + "technical_server_installation WHERE singleton AND "
                + "access_mode IS NOT NULL")
        .query(
            (row, number) ->
                new PublicIdentity(
                    new ServerInstanceId(row.getString(1)),
                    ServerAccessMode.parse(row.getString(2))))
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
  }

  /** Only installation identity and explicitly committed mode are public. */
  public record PublicIdentity(ServerInstanceId instance, ServerAccessMode mode) {
    @Override
    public String toString() {
      return "PublicIdentity[redacted]";
    }
  }

  /** Non-secret metadata kept out of diagnostic representations. */
  public record TechnicalIdentity(ServerInstanceId instance, SynchronizationScopeId scope) {
    @Override
    public String toString() {
      return "TechnicalIdentity[redacted]";
    }
  }
}
