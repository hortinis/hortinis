package com.hortinis.sync.authentication;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
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
    };
  }

  /** Spring Session owns cookie publication, rotation and expiration. */
  @Bean
  public static CookieHttpSessionIdResolver authenticationSessionIdResolver() {
    var serializer = new DefaultCookieSerializer();
    serializer.setCookieName("__Host-hortinis-session");
    serializer.setCookiePath("/");
    serializer.setUseSecureCookie(true);
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Strict");
    serializer.setUseBase64Encoding(false);
    var resolver = new CookieHttpSessionIdResolver();
    resolver.setCookieSerializer(serializer);
    return resolver;
  }
}
