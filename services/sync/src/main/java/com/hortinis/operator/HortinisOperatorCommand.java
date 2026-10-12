package com.hortinis.operator;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationOperatorTickets;
import com.hortinis.sync.authentication.OperatorTicketFiles;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.BeansException;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

/**
 * Explicit local process capability; this package is outside the web application's component scan.
 */
public final class HortinisOperatorCommand {
  private static final String AUTH_MODE = "single-user-auth";
  private static final String MODE_PROPERTY = "hortinis.access.mode";
  private static final String DIRECTORY_PROPERTY = "hortinis.operator.directory";

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
    OperatorCommandOptions options;
    try {
      options = OperatorCommandOptions.parse(args);
    } catch (IllegalArgumentException exception) {
      error.println("Invalid operator command.");
      return 2;
    }
    if (!AUTH_MODE.equals(new StandardEnvironment().getProperty(MODE_PROPERTY))) {
      error.println("Operator command requires explicit single-user authentication configuration.");
      return 1;
    }
    var application = new SpringApplication(OperatorConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.setRegisterShutdownHook(false);
    try (var context =
        application.run(
            "--spring.main.web-application-type=none",
            "--spring.main.banner-mode=off",
            "--spring.main.log-startup-info=false",
            "--logging.config=classpath:authentication/operator-logback.xml",
            "--logging.level.root=OFF",
            "--hortinis.sync.persistence.enabled=true",
            "--" + DIRECTORY_PROPERTY + "=" + options.directory())) {
      var tickets = context.getBean(AuthenticationOperatorTickets.class);
      switch (options.action()) {
        case SETUP -> tickets.issueSetup();
        case RECOVERY -> tickets.issueRecovery(options.account());
        case RECONCILE -> tickets.reconcile();
        default -> throw new IllegalStateException("Invalid operator action.");
      }
    } catch (BeansException
        | DataAccessException
        | TransactionException
        | AuthenticationFailedException
        | IllegalArgumentException
        | IllegalStateException exception) {
      error.println("Operator command failed.");
      return 1;
    }
    output.println(
        options.action() == OperatorCommandOptions.Action.RECONCILE
            ? "Operator ticket reconciliation completed."
            : "Operator ticket written to protected storage.");
    return 0;
  }

  /**
   * Imported only by explicit non-web dispatch; the ordinary application cannot scan this graph.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {UserDetailsServiceAutoConfiguration.class, JdbcSessionAutoConfiguration.class})
  @Import(AuthenticationOperatorTicketPersistence.class)
  public static class OperatorConfiguration {
    /** Trusted UTC is shared with the existing exact-ticket component. */
    @Bean
    public Clock operatorClock() {
      return Clock.systemUTC();
    }

    /** Validate existing protected storage and selected mode before issuing any host capability. */
    @Bean
    public AuthenticationOperatorTickets operatorTickets(
        Environment environment,
        AuthenticationOperatorTicketPersistence persistence,
        PlatformTransactionManager transactions) {
      if (!AUTH_MODE.equals(environment.getProperty(MODE_PROPERTY))) {
        throw new IllegalStateException("Invalid operator configuration.");
      }
      String directory = environment.getRequiredProperty(DIRECTORY_PROPERTY);
      return new AuthenticationOperatorTickets(
          persistence, new OperatorTicketFiles(Path.of(directory)), transactions);
    }
  }
}
