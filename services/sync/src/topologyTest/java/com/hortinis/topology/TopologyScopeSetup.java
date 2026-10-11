package com.hortinis.topology;

import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.TopologyAccessResolver;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Controlled, restart-safe synthetic setup, separate from all request workflows. */
final class TopologyScopeSetup {
  private TopologyScopeSetup() {}

  static void provision(JdbcClient jdbc, ServerInstanceId instance) {
    for (String scope :
        new String[] {TopologyAccessResolver.SCOPE_A, TopologyAccessResolver.SCOPE_B}) {
      jdbc.sql(
              "INSERT INTO technical_synchronization_scope "
                  + "(synchronization_scope_id, server_instance_id) VALUES (?, ?) "
                  + "ON CONFLICT (synchronization_scope_id) DO NOTHING")
          .params(UUID.fromString(scope), UUID.fromString(instance.value()))
          .update();
      boolean matches =
          jdbc.sql(
                  "SELECT EXISTS (SELECT 1 FROM technical_synchronization_scope WHERE "
                      + "synchronization_scope_id = ? AND server_instance_id = ? "
                      + "AND NOT technical_default)")
              .params(UUID.fromString(scope), UUID.fromString(instance.value()))
              .query(Boolean.class)
              .single();
      if (!matches) {
        throw new IllegalStateException("Synthetic topology scope setup is inconsistent.");
      }
    }
  }
}
