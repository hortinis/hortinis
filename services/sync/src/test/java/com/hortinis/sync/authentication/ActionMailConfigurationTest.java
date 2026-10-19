package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;

class ActionMailConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
          .withUserConfiguration(ActionMailConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(
              org.springframework.jdbc.core.simple.JdbcClient.class,
              () -> mock(org.springframework.jdbc.core.simple.JdbcClient.class))
          .withBean(ActionTokenPersistence.class, () -> mock(ActionTokenPersistence.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withPropertyValues(
              "hortinis.access.mode=single-user-auth",
              "spring.mail.host=127.0.0.1",
              "spring.mail.port=9",
              "spring.mail.properties[mail.smtp.auth]=false",
              "spring.mail.properties[mail.smtp.starttls.enable]=true",
              "spring.mail.properties[mail.smtp.starttls.required]=true",
              "spring.mail.properties[mail.smtp.ssl.checkserveridentity]=true",
              "spring.mail.properties[mail.smtp.connectiontimeout]=5000",
              "spring.mail.properties[mail.smtp.timeout]=5000",
              "spring.mail.properties[mail.smtp.writetimeout]=5000",
              "hortinis.authentication.mail-from=Hortinis@Example.test",
              "hortinis.access.public-origin=https://garden.example.test");

  @Test
  void bootCreatesSenderWithoutConnectingAndAppliesAllThreeTimeouts() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(ActionMailDelivery.class);
          var sender = context.getBean(JavaMailSenderImpl.class);
          for (String name : List.of("connectiontimeout", "timeout", "writetimeout")) {
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp." + name))
                .isEqualTo("5000");
          }
          assertThat(sender.getSession().getDebug()).isFalse();
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.mail.properties.mail.debug=true",
        "spring.mail.properties[mail.smtp.auth]=true",
        "spring.mail.host=",
        "spring.mail.port=0",
        "spring.mail.port=65536",
        "spring.mail.port=not-a-port",
        "spring.mail.properties[mail.smtp.auth]=",
        "spring.mail.properties[mail.smtp.connectiontimeout]=0",
        "spring.mail.properties[mail.smtp.timeout]=-1",
        "spring.mail.properties[mail.smtp.writetimeout]=6000",
        "spring.mail.properties[mail.smtp.starttls.required]=false",
        "spring.mail.properties[mail.smtp.ssl.checkserveridentity]=false",
        "spring.mail.properties[mail.debug]=true",
        "spring.mail.properties[mail.debug.auth]=true",
        "spring.mail.properties[mail.smtp.ssl.trust]=*",
        "spring.mail.test-connection=true",
        "management.health.mail.enabled=true",
        "spring.mail.jndi-name=synthetic",
        "spring.mail.protocol=smtps",
        "hortinis.authentication.mail-from=invalid",
        "hortinis.access.public-origin=https://garden.example.test/redirect"
      })
  void invalidConfigurationFailsWithFixedApplicationDiagnostic(String property) {
    runner
        .withPropertyValues(property)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("Authenticated mail configuration is invalid.");
            });
  }

  @Test
  void noAuthNeedsNoMailConfigurationOrApplicationDeliveryBeans() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
        .withUserConfiguration(ActionMailConfiguration.class)
        .withPropertyValues("hortinis.access.mode=single-user-no-auth")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ActionMailDelivery.class)
                    .doesNotHaveBean(ActionTokenDelivery.class));
  }
}
