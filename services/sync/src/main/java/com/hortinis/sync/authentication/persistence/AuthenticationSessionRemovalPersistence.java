package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.UUID;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical native/authority removal inside the caller's ordered account/session transaction. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionRemovalPersistence {
  private final JdbcClient jdbc;
  private final ConversionService codec = SessionAttributeCodec.conversionService();

  /** Shares the application's datasource; no asynchronous save can restore removed authority. */
  public AuthenticationSessionRemovalPersistence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Caller holds the current account lock before removing its ordered parent rows. */
  public void removeAll(UUID account) {
    requireTransaction();
    var references =
        jdbc.sql(
                "SELECT session_reference FROM authentication_session_authority "
                    + "WHERE account_id = ? ORDER BY session_reference FOR UPDATE")
            .param(account)
            .query(UUID.class)
            .list();
    for (UUID reference : references) {
      remove(reference);
    }
  }

  /** Caller has locked this exact parent, or established that its authority is already absent. */
  public void remove(UUID reference) {
    requireTransaction();
    byte[] encoded = codec.convert(new SessionAuthorityReference(reference), byte[].class);
    jdbc.sql(
            "DELETE FROM spring_session WHERE primary_id IN "
                + "(SELECT session_primary_id FROM spring_session_attributes "
                + "WHERE attribute_name = 'hortinis.authority' AND attribute_bytes = ?)")
        .param(encoded)
        .update();
    jdbc.sql("DELETE FROM authentication_session_authority WHERE session_reference = ?")
        .param(reference)
        .update();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Session removal requires an ordered writable transaction.");
    }
  }
}
