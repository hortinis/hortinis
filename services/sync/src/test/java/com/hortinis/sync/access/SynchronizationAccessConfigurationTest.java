package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.configuration.SynchronizationAccessConfiguration;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class SynchronizationAccessConfigurationTest {

  private final ServerInstallationPersistence installation =
      mock(ServerInstallationPersistence.class);
  private final CurrentAuthenticationAuthorityPersistence authority =
      mock(CurrentAuthenticationAuthorityPersistence.class);
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SynchronizationAccessConfiguration.class)
          .withBean(ServerInstallationPersistence.class, () -> installation);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"unknown-private-mode", "test", "SINGLE_USER_NO_AUTH", " single-user-no-auth"})
  void rejectsMissingAndUnknownModesWithoutEchoingConfiguration(
      String value, CapturedOutput output) {
    var configured =
        value == null
            ? runner
            : runner.withInitializer(
                context ->
                    context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(
                            new MapPropertySource(
                                "access-mode-test", Map.of("hortinis.access.mode", value))));
    configured.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseMessage("An explicit supported server access mode is required.");
          verifyNoInteractions(installation);
        });
    assertThat(output.getAll())
        .doesNotContain("unknown-private-mode", "application_authentication_disabled");
  }

  @ParameterizedTest
  @ValueSource(strings = {"single-user-auth"})
  void authenticationCannotBeDisabledOrReplacedByTestProfile(String mode) {
    runner
        .withBean(CurrentAuthenticationAuthorityPersistence.class, () -> authority)
        .withPropertyValues(
            "hortinis.access.mode=" + mode,
            "spring.profiles.active=test",
            "hortinis.access.authentication.enabled=false",
            "hortinis.access.test-resolver.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "Authenticated production access requires separate activation approval.");
              verifyNoInteractions(installation, authority);
            });
  }

  @Test
  void multiUserModeStillFailsClosed() {
    runner
        .withPropertyValues("hortinis.access.mode=multi-user-auth")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("Multi-user server access is not implemented.");
              verifyNoInteractions(installation, authority);
            });
  }

  @Test
  void explicitNoAuthValidatesSetupAndEmitsOnlyConfigurationWarning(CapturedOutput output) {
    runner
        .withPropertyValues(
            "hortinis.access.mode=single-user-no-auth",
            "spring.profiles.active=test",
            "hortinis.access.test-resolver.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SynchronizationAccessResolver.class);
              assertThat(context.getBean(SynchronizationAccessResolver.class))
                  .isExactlyInstanceOf(ConfiguredSynchronizationAccessResolver.class);
              verify(installation).initializeMode(ServerAccessMode.SINGLE_USER_NO_AUTH);
            });
    var warnings =
        output
            .getAll()
            .lines()
            .filter(line -> line.contains("Application authentication is disabled;"))
            .toList();
    assertThat(warnings).hasSize(1);
  }

  @Test
  void inconsistentSetupFailsBeforeResolverActivationOrWarning(CapturedOutput output) {
    doThrow(new SynchronizationAccessDeniedException())
        .when(installation)
        .initializeMode(ServerAccessMode.SINGLE_USER_NO_AUTH);
    runner
        .withPropertyValues("hortinis.access.mode=single-user-no-auth")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage("Access is denied.");
            });
    assertThat(output.getAll()).doesNotContain("Application authentication is disabled;");
  }

  @Test
  void disabledSynchronizationDoesNotCreateAnAccessResolver() {
    runner
        .withPropertyValues("hortinis.sync.persistence.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(SynchronizationAccessResolver.class);
              verifyNoInteractions(installation);
            });
  }
}
