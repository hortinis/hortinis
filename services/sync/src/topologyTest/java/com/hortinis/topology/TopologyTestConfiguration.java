package com.hortinis.topology;

import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.access.TopologyAccessResolver;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit test-launcher import outside production component scanning and artifacts. */
@Configuration(proxyBeanMethods = false)
public class TopologyTestConfiguration {
  /**
   * Setup is completed once before requests; the ordinary configured resolver still validates mode.
   */
  @Bean
  @Primary
  public SynchronizationAccessResolver topologyAccessResolver(
      @Qualifier("synchronizationAccessResolver") SynchronizationAccessResolver configured,
      ServerInstallationPersistence installation,
      JdbcClient jdbc,
      PlatformTransactionManager transactions,
      HttpServletRequest request) {
    var instance = configured.resolve().serverInstanceId();
    new TransactionTemplate(transactions)
        .executeWithoutResult(ignored -> TopologyScopeSetup.provision(jdbc, instance));
    return new TopologyAccessResolver(installation, request);
  }
}
