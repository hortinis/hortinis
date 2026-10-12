package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import java.util.Map;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Established JDBC transport configuration; authoritative rows independently govern data access.
 */
public final class AuthenticationJdbcSessions {
  private AuthenticationJdbcSessions() {}

  /**
   * Admission and rotation callers must use the shared writable publication transaction. Rotation
   * requires an explicit repository save; cookie release is permitted only after successful commit.
   */
  public static JdbcIndexedSessionRepository create(
      JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    JdbcIndexedSessionRepository repository = new JdbcIndexedSessionRepository(jdbc, transactions);
    repository.setSessionIdGenerator(AuthenticationSecrets::randomValue);
    repository.setConversionService(SessionAttributeCodec.conversionService());
    repository.setIndexResolver(session -> Map.of());
    repository.setDefaultMaxInactiveInterval(AuthenticationLifetime.FULL_ABSOLUTE);
    repository.setFlushMode(FlushMode.IMMEDIATE);
    repository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
    // Ordered authority cleanup owns physical retention, rather than a second uncoordinated job.
    repository.setCleanupCron(Scheduled.CRON_DISABLED);
    return repository;
  }
}
