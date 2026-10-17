package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

/** SMTP is required only in the closed authenticated graph; configuration never probes delivery. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
public class ActionMailConfiguration {
  /** Per-process candidate quotas; Q1/Q2 are unresolved and production remains closed. */
  @Bean
  public ActionMailAdmission actionMailAdmission(
      Clock clock, AuthenticationAdmissionCapacity capacity) {
    return new ActionMailAdmission(clock, capacity);
  }

  /** Boot-managed sender with fixed application configuration and finite I/O bounds. */
  @Bean
  @ConditionalOnMissingBean(ActionMailDelivery.class)
  public ActionMailDelivery actionMailDelivery(
      ObjectProvider<JavaMailSender> sender, Environment environment) {
    try {
      validate(environment);
      JavaMailSender configuredSender = sender.getIfAvailable();
      if (configuredSender == null) {
        throw invalid();
      }
      return new SmtpActionMailDelivery(
          configuredSender,
          environment.getProperty("hortinis.authentication.mail-from"),
          environment.getProperty("hortinis.access.public-origin"));
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  /** No action endpoint or authentication publication is installed by this boundary. */
  @Bean
  public ActionTokenDelivery actionTokenDelivery(
      ActionTokenPersistence tokens,
      ActionMailAdmission admission,
      ActionMailDelivery delivery,
      PlatformTransactionManager transactions) {
    return new ActionTokenDelivery(tokens, admission, delivery, transactions);
  }

  private static void validate(Environment environment) {
    String host = environment.getProperty("spring.mail.host", "");
    String port = environment.getProperty("spring.mail.port", "");
    Map<String, String> properties =
        Binder.get(environment)
            .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
    String auth = properties.getOrDefault("mail.smtp.auth", "");
    if (host.isBlank()
        || host.chars().anyMatch(Character::isWhitespace)
        || !port.matches("[0-9]{1,5}")
        || Integer.parseInt(port) < 1
        || Integer.parseInt(port) > 65535
        || !("true".equals(auth) || "false".equals(auth))
        || !"smtp".equals(environment.getProperty("spring.mail.protocol", "smtp"))
        || environment.containsProperty("spring.mail.jndi-name")
        || environment.getProperty("spring.mail.test-connection", Boolean.class, false)
        || environment.getProperty("management.health.mail.enabled", Boolean.class, false)) {
      throw invalid();
    }
    if ("true".equals(auth)
        && (environment.getProperty("spring.mail.username", "").isBlank()
            || environment.getProperty("spring.mail.password", "").isEmpty())) {
      throw invalid();
    }
    for (String property : List.of("connectiontimeout", "timeout", "writetimeout")) {
      if (!"5000".equals(properties.get("mail.smtp." + property))) {
        throw invalid();
      }
    }
    for (String property :
        List.of("starttls.enable", "starttls.required", "ssl.checkserveridentity")) {
      if (!"true".equals(properties.get("mail.smtp." + property))) {
        throw invalid();
      }
    }
    for (String property : List.of("mail.debug", "mail.debug.auth")) {
      if (Boolean.parseBoolean(properties.get(property))) {
        throw invalid();
      }
    }
    if (properties.containsKey("mail.smtp.ssl.trust")
        || properties.containsKey("mail.smtp.socketFactory.class")
        || properties.containsKey("mail.smtp.ssl.socketFactory.class")) {
      throw invalid();
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Authenticated mail configuration is invalid.");
  }
}
