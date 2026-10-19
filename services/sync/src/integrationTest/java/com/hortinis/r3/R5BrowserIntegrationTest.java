package com.hortinis.r3;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.HortinisSyncApplication;
import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.authentication.ActionMailDelivery;
import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.CryptoWorkLimiter;
import com.hortinis.sync.authentication.PasswordVerifier;
import com.hortinis.sync.authentication.SmtpActionMailDelivery;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.configuration.SynchronizationAccessConfiguration;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Fresh real-browser replacement evidence. Never available in either production archive. */
@Testcontainers
@SpringBootTest(
    classes = R5BrowserIntegrationTest.Fixture.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext
class R5BrowserIntegrationTest {
  private static final String PASSWORD = " exact +&=🌱 synthetic password ";
  private static final String KEYSTORE_FILE = "runtime.p12";
  private static final String TLS_PASSWORD = "synthetic-r5-only";
  private static final Path TLS = createTls();
  private static final int FRONT_PORT = freePort();
  private static final String ORIGIN = "https://localhost:" + FRONT_PORT;
  private static final String VERIFIER =
      new PasswordVerifier(new CryptoWorkLimiter()).encode(PASSWORD);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @LocalServerPort private int port;
  @Autowired private Control control;
  @MockitoBean private AuthenticationAttemptLimiter attempts;
  @MockitoBean private ActionMailDelivery delivery;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("hortinis.access.mode", () -> "single-user-auth");
    registry.add("hortinis.access.public-origin", () -> ORIGIN);
    registry.add("hortinis.access.tls-validated", () -> "true");
    registry.add("server.ssl.enabled", () -> "true");
    registry.add("server.ssl.key-store", () -> TLS.resolve(KEYSTORE_FILE).toString());
    registry.add("server.ssl.key-store-password", () -> TLS_PASSWORD);
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-alias", () -> "hortinis-test");
  }

  @Test
  void angularNativeSessionAndOfflineBoundaries() throws Exception {
    org.mockito.Mockito.when(delivery.deliver(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> control.deliver(call.getArgument(0)));
    control.attempts = attempts;
    Path repository = Path.of(System.getProperty("hortinis.repository.root"));
    var process =
        new ProcessBuilder("node", "apps/web/e2e/r5-browser.mjs").directory(repository.toFile());
    process.environment().put("R5_BACKEND", "https://localhost:" + port);
    process.environment().put("R5_ORIGIN", ORIGIN);
    process.environment().put("R5_TLS", TLS.resolve(KEYSTORE_FILE).toString());
    process.environment().put("R5_TLS_PASSWORD", TLS_PASSWORD);
    Path output = TLS.resolve("browser.log");
    var running = process.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertThat(running.waitFor(240, TimeUnit.SECONDS)).as("R5 browser completion").isTrue();
      assertThat(running.exitValue())
          .withFailMessage("R5 browser failed: %s", Files.readString(output))
          .isZero();
      assertThat(Files.readString(output)).contains("R5 browser scenarios passed: 25");
      System.out.println("R5 browser scenarios passed: 25");
    } finally {
      running.destroyForcibly();
    }
  }

  /** This source-only graph omits only the closed admission bean, like R2/R4 fixtures. */
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
  @ComponentScan(
      basePackages = "com.hortinis.sync",
      excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {HortinisSyncApplication.class, SynchronizationAccessConfiguration.class}),
        @ComponentScan.Filter(
            type = FilterType.ANNOTATION,
            classes = org.springframework.boot.test.context.TestConfiguration.class)
      })
  static class Fixture {
    @Bean
    ServerAccessMode serverAccessMode() {
      return ServerAccessMode.SINGLE_USER_AUTH;
    }

    @Bean
    @DependsOnDatabaseInitialization
    SynchronizationAccessResolver resolver(
        ServerInstallationPersistence installation,
        CurrentAuthenticationAuthorityPersistence authority) {
      installation.initializeMode(ServerAccessMode.SINGLE_USER_AUTH);
      return new AuthenticatedSynchronizationAccessResolver(authority);
    }

    @Bean
    Control control(
        JdbcTemplate jdbc,
        ActionTokenPersistence tokens,
        PlatformTransactionManager manager,
        com.hortinis.sync.authentication.persistence.MailIssuancePersistence quotas) {
      return new Control(jdbc, tokens, manager, quotas);
    }

    @Bean
    WebSecurityCustomizer testControlOnly() {
      return web -> web.ignoring().requestMatchers("/__r5/control");
    }
  }

  /** Test controls use only synthetic state and are excluded from the production classpath. */
  @RestController
  static final class Control {
    private static final String FILL_RESET_ALLOCATION = "fill-reset-allocation";
    private static final String EXPIRE = "expire";
    private static final String REVOKE = "revoke";
    private static final String EXPIRE_SESSION = "expire-session";
    private static final String DIFFERENT_ACCOUNT = "different-account";
    private static final String SMTP_FAILURE = "smtp-failure";
    private final JdbcTemplate jdbc;
    private final ActionTokenPersistence tokens;
    private final TransactionTemplate transactions;
    private final com.hortinis.sync.authentication.persistence.MailIssuancePersistence quotas;
    private AuthenticationAttemptLimiter attempts;
    private UUID account;
    private String secret = "";
    private boolean smtpFailure;
    private int deliveries;

    Control(
        JdbcTemplate jdbc,
        ActionTokenPersistence tokens,
        PlatformTransactionManager manager,
        com.hortinis.sync.authentication.persistence.MailIssuancePersistence quotas) {
      this.quotas = quotas;
      this.jdbc = jdbc;
      this.tokens = tokens;
      transactions = new TransactionTemplate(manager);
    }

    @GetMapping("/__r5/control")
    Map<String, Object> control(@RequestParam String action) {
      if (action.startsWith("fresh-")) {
        jdbc.execute(
            "TRUNCATE authentication_account, spring_session, authentication_security_event, "
                + "technical_record, accepted_technical_record_operation, technical_record_change, "
                + "technical_record_tombstone, retired_technical_record_identifier, "
                + "technical_exchange_expectation CASCADE");
        jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 0");
        String status = action.substring(6);
        boolean pending = "pending".equals(status);
        account =
            jdbc.queryForObject(
                "INSERT INTO authentication_account "
                    + "(server_instance_id, synchronization_scope_id, delivery_email, "
                    + "canonical_email, status, "
                    + "password_verifier, created_at, verified_at) "
                    + "SELECT server_instance_id, synchronization_scope_id, "
                    + "'Gardener+tag@Example.test', 'gardener+tag@example.test', ?, ?, now(), ? "
                    + "FROM technical_synchronization_scope RETURNING account_id",
                UUID.class,
                "unverified".equals(status) ? "active" : status,
                pending ? null : VERIFIER,
                pending || "unverified".equals(status)
                    ? null
                    : java.sql.Timestamp.from(java.time.Instant.now()));
        var realAttempts = new AuthenticationAttemptLimiter(Clock.systemUTC());
        org.mockito.Mockito.doAnswer(
                call -> realAttempts.admit(call.getArgument(0), call.getArgument(1)))
            .when(attempts)
            .admit(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(call -> realAttempts.admitSource(call.getArgument(0)))
            .when(attempts)
            .admitSource(org.mockito.ArgumentMatchers.anyString());
        jdbc.update("DELETE FROM authentication_mail_reservation");
        jdbc.update("DELETE FROM authentication_mail_quota");
        secret = "";
        deliveries = 0;
        smtpFailure = false;
      } else if ("invitation".equals(action) || "reset".equals(action)) {
        var issued =
            transactions.execute(
                status ->
                    tokens.issue(
                        account,
                        "invitation".equals(action)
                            ? ActionTokenPurpose.INVITATION
                            : ActionTokenPurpose.RESET));
        secret = issued.secret();
      } else if (EXPIRE.equals(action)) {
        jdbc.update(
            "UPDATE authentication_action_token SET created_at = now() - INTERVAL '11 minutes', "
                + "expires_at = now() - INTERVAL '1 minute' ");
      } else if (REVOKE.equals(action)) {
        jdbc.update("UPDATE authentication_account SET security_version = security_version + 1");
      } else if (EXPIRE_SESSION.equals(action)) {
        jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0");
      } else if (DIFFERENT_ACCOUNT.equals(action)) {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM authentication_account");
        account =
            jdbc.queryForObject(
                "INSERT INTO authentication_account "
                    + "(server_instance_id, synchronization_scope_id, delivery_email, "
                    + "canonical_email, status, "
                    + "password_verifier, created_at, verified_at) "
                    + "SELECT server_instance_id, synchronization_scope_id, "
                    + "'Gardener+tag@Example.test', 'gardener+tag@example.test', "
                    + "'active', ?, now(), now() "
                    + "FROM technical_synchronization_scope RETURNING account_id",
                UUID.class,
                VERIFIER);
      } else if (FILL_RESET_ALLOCATION.equals(action)) {
        transactions.executeWithoutResult(
            status -> {
              for (int index = 0; index < 20; index++) {
                assertThat(
                        quotas.reserve(
                            "synthetic" + index + "@example.test", ActionTokenPurpose.RESET))
                    .isTrue();
              }
            });
      } else if (SMTP_FAILURE.equals(action)) {
        smtpFailure = true;
      } else if (!"observe".equals(action)) {
        throw new IllegalArgumentException("Unknown synthetic control.");
      }
      return Map.of(
          "token",
          secret,
          "deliveries",
          deliveries,
          "reservations",
          jdbc.queryForObject(
              "SELECT count(*) FROM authentication_mail_reservation", Integer.class),
          "status",
          jdbc.queryForObject("SELECT status FROM authentication_account", String.class));
    }

    private synchronized boolean deliver(ActionTokenPersistence.IssuedToken token)
        throws Exception {
      try (var smtp = new LocalSmtpFixture(false, smtpFailure)) {
        boolean accepted =
            new SmtpActionMailDelivery(smtp.sender(), "sender@example.test", ORIGIN).deliver(token);
        if (accepted) {
          assertThat(smtp.message()).contains("#token=");
          deliveries++;
        }
        secret = token.secret();
        return accepted;
      }
    }
  }

  private static int freePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static Path createTls() {
    try {
      Path directory = Files.createTempDirectory("hortinis-r5-");
      var process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "hortinis-test",
                  "-keyalg",
                  "EC",
                  "-groupname",
                  "secp256r1",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1",
                  "-validity",
                  "1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  directory.resolve(KEYSTORE_FILE).toString(),
                  "-storepass",
                  TLS_PASSWORD,
                  "-keypass",
                  TLS_PASSWORD,
                  "-noprompt")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("Synthetic TLS failed.");
      }
      var store = java.security.KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(directory.resolve(KEYSTORE_FILE))) {
        store.load(input, TLS_PASSWORD.toCharArray());
      }
      String certificate =
          java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
              .encodeToString(store.getCertificate("hortinis-test").getEncoded());
      Files.writeString(
          directory.resolve("runtime.pem"),
          "-----BEGIN CERTIFICATE-----\n" + certificate + "\n-----END CERTIFICATE-----\n");
      return directory;
    } catch (IOException
        | InterruptedException
        | java.security.GeneralSecurityException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @AfterAll
  static void cleanup() throws IOException {
    Files.deleteIfExists(TLS.resolve(KEYSTORE_FILE));
    Files.deleteIfExists(TLS.resolve("browser.log"));
    Files.deleteIfExists(TLS.resolve("runtime.pem"));
    Files.delete(TLS);
  }
}
