package com.hortinis.sync.configuration;

import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.access.ConfiguredSynchronizationAccessResolver;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Closed, explicit access configuration; authentication cannot fall back to no-auth access. */
@Configuration(proxyBeanMethods = false)
@SynchronizationPersistenceEnabled
public class SynchronizationAccessConfiguration {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(SynchronizationAccessConfiguration.class);

  /** Rejects incomplete/unknown configuration and the still unsupported multi-user mode. */
  @Bean
  public ServerAccessMode serverAccessMode(Environment environment) {
    ServerAccessMode mode;
    try {
      mode = ServerAccessMode.parse(environment.getProperty("hortinis.access.mode"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("An explicit supported server access mode is required.");
    }
    if (mode == ServerAccessMode.MULTI_USER_AUTH) {
      throw new IllegalStateException("Multi-user server access is not implemented.");
    }
    if (mode == ServerAccessMode.SINGLE_USER_AUTH) {
      throw new IllegalStateException(
          "Authenticated production access requires separate activation approval.");
    }
    return mode;
  }

  /** Validates committed setup after Flyway, then installs the sole production resolver. */
  @Bean
  @DependsOnDatabaseInitialization
  @ConditionalOnProperty(
      prefix = "hortinis.access",
      name = "mode",
      havingValue = "single-user-no-auth")
  public SynchronizationAccessResolver synchronizationAccessResolver(
      ServerAccessMode mode, ServerInstallationPersistence installation) {
    installation.initializeMode(mode);
    LOGGER
        .atWarn()
        .addKeyValue("event", "application_authentication_disabled")
        .log("Application authentication is disabled; reachable clients can read and modify data.");
    return new ConfiguredSynchronizationAccessResolver(installation);
  }

  /** Installs authentication authority only after a fresh sole-scope mode claim commits. */
  @Bean
  @DependsOnDatabaseInitialization
  @ConditionalOnProperty(
      prefix = "hortinis.access",
      name = "mode",
      havingValue = "single-user-auth")
  public SynchronizationAccessResolver authenticatedSynchronizationAccessResolver(
      ServerAccessMode mode,
      ServerInstallationPersistence installation,
      CurrentAuthenticationAuthorityPersistence authority) {
    installation.initializeMode(mode);
    return new AuthenticatedSynchronizationAccessResolver(authority);
  }
}
