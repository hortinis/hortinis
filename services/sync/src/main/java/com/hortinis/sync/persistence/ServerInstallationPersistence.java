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
    if (mode != ServerAccessMode.SINGLE_USER_NO_AUTH) {
      throw new IllegalStateException("Authenticated server access is not implemented.");
    }
    jdbc.sql("SELECT singleton FROM technical_server_installation WHERE singleton FOR UPDATE")
        .query(Boolean.class)
        .optional()
        .orElseThrow(SynchronizationAccessDeniedException::new);
    int changed =
        jdbc.sql(
                "UPDATE technical_server_installation SET access_mode = ? "
                    + "WHERE singleton AND (access_mode IS NULL OR access_mode = ?)")
            .params(mode.wireValue(), mode.wireValue())
            .update();
    if (changed != SINGLE_INSTALLATION) {
      throw new IllegalStateException("The configured access mode differs from committed setup.");
    }
    technicalIdentity();
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

  /** Non-secret metadata kept out of diagnostic representations. */
  public record TechnicalIdentity(ServerInstanceId instance, SynchronizationScopeId scope) {
    @Override
    public String toString() {
      return "TechnicalIdentity[redacted]";
    }
  }
}
