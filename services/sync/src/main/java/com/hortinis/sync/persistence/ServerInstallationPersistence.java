package com.hortinis.sync.persistence;

import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the explicit sole technical scope provisioned by the fresh-install migration. */
@Repository
@SynchronizationPersistenceEnabled
public class ServerInstallationPersistence {
  private final JdbcClient jdbc;

  /** Reads installation metadata through JDBC. */
  public ServerInstallationPersistence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Denies a missing explicitly designated scope instead of selecting any available scope. */
  public TechnicalIdentity technicalIdentity() {
    return jdbc.sql(
            "SELECT i.server_instance_id::text, s.synchronization_scope_id::text "
                + "FROM technical_server_installation i JOIN technical_synchronization_scope s "
                + "ON s.server_instance_id = i.server_instance_id "
                + "WHERE i.singleton AND s.technical_default")
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
