package com.hortinis.operator;

import com.hortinis.sync.authentication.ActionTokenDelivery;
import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.authentication.persistence.InvitationPersistence;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit local process capability; this package is outside the web application's component scan.
 */
public final class HortinisOperatorCommand {
  private static final int MAXIMUM_INPUT_BYTES = 256;

  private HortinisOperatorCommand() {}

  /**
   * A misplaced operator token still enters the closed parser instead of starting an HTTP server.
   */
  public static boolean isInvocation(String[] args) {
    return args != null
        && Arrays.stream(args)
            .anyMatch(
                value ->
                    "operator".equals(value) || value != null && value.startsWith("--operator"));
  }

  /** Last-resort process boundary also redacts unexpected errors before the JVM prints a trace. */
  public static void installUncaughtFailureBoundary() {
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> System.err.println("Operator command failed."));
  }

  /** Only fixed outcomes leave this process; no configuration value or exception is printed. */
  public static int run(String[] args, PrintStream output, PrintStream error) {
    return run(args, System.in, output, error);
  }

  /** The bounded input is consumed before Boot, JDBC or mail configuration is initialized. */
  // The process boundary must redact arbitrary framework failures.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
    OperatorCommandOptions options;
    String email;
    try {
      options = OperatorCommandOptions.parse(args);
      byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
      if (bytes.length > MAXIMUM_INPUT_BYTES) {
        throw new IllegalArgumentException();
      }
      email = new String(bytes, StandardCharsets.US_ASCII);
      if (email.endsWith("\n")) {
        email = email.substring(0, email.length() - 1);
        if (email.endsWith("\r")) {
          email = email.substring(0, email.length() - 1);
        }
      }
      EmailAddress.canonical(email);
    } catch (IllegalArgumentException | IOException exception) {
      error.println("Invalid operator command or input.");
      return 2;
    }
    System.setProperty("logging.config", "classpath:authentication/operator-logback.xml");
    try (var context =
        new SpringApplicationBuilder(InvitationCommandConfiguration.class)
            .web(WebApplicationType.NONE)
            .initializers(
                candidate -> {
                  if (!(candidate instanceof AnnotationConfigApplicationContext)
                      || !"single-user-auth"
                          .equals(candidate.getEnvironment().getProperty("hortinis.access.mode"))) {
                    throw new IllegalStateException(
                        "Operator context requires non-web authenticated mode.");
                  }
                })
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .logStartupInfo(false)
            .registerShutdownHook(false)
            .run()) {
      var invitations = context.getBean(InvitationPersistence.class);
      final String target = email;
      if (options.action() == OperatorCommandOptions.Action.CANCEL) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
            .executeWithoutResult(status -> invitations.cancel(target));
        output.println("Invitation cancelled.");
        return 0;
      }
      var result =
          context
              .getBean(ActionTokenDelivery.class)
              .issueAndDeliver(
                  email,
                  ActionTokenPurpose.INVITATION,
                  () ->
                      options.action() == OperatorCommandOptions.Action.INVITE
                          ? invitations.provision(target)
                          : invitations.pending(target));
      if (result == ActionTokenDelivery.Outcome.ACCEPTED) {
        output.println("Invitation committed and SMTP accepted.");
        return 0;
      }
      error.println(
          result == ActionTokenDelivery.Outcome.UNAVAILABLE
              ? "Invitation committed; delivery failed. Resend after the cooldown."
              : "Invitation attempt is temporarily limited.");
      return 1;
    } catch (RuntimeException exception) {
      error.println("Operator command failed.");
      return 1;
    }
  }
}
