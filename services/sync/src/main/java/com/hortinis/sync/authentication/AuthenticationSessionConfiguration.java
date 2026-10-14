package com.hortinis.sync.authentication;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Supported Boot session extension points; Boot constructs the repository and filter. */
@Configuration(proxyBeanMethods = false)
@SynchronizationPersistenceEnabled
public class AuthenticationSessionConfiguration {
  /** Session writes participate in the existing publication transaction, including rollback. */
  @Bean(name = "springSessionTransactionOperations")
  public TransactionOperations springSessionTransactionOperations(
      PlatformTransactionManager transactions) {
    return new TransactionTemplate(transactions);
  }

  /** Retains bounded identifiers and closed attributes without Java object deserialization. */
  @Bean
  public SessionRepositoryCustomizer<JdbcIndexedSessionRepository>
      authenticationSessionCustomizer() {
    return repository -> {
      repository.setSessionIdGenerator(AuthenticationSecrets::randomValue);
      repository.setConversionService(SessionAttributeCodec.conversionService());
      repository.setIndexResolver(session -> Map.of());
    };
  }

  /** Cookie publication remains fenced to committed explicit authentication workflows. */
  @Bean
  public HttpSessionIdResolver authenticationSessionIdResolver() {
    return new ControlledSessionIdResolver();
  }
}
