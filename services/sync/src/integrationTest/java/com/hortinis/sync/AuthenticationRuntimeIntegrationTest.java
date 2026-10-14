package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs selected S9 mode through the real Boot filter chain and a clean PostgreSQL schema. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthenticationRuntimeIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  private static final Path OPERATOR_DIRECTORY = createOperatorDirectory();
  private static final String BOOTSTRAP_PATH = "/api/v1/sync/bootstrap";
  private static final String FETCH_SITE = "Sec-Fetch-Site";
  private static final String FETCH_MODE = "Sec-Fetch-Mode";
  private static final String FETCH_DEST = "Sec-Fetch-Dest";
  private static final String SAME_ORIGIN = "same-origin";
  private static final String CORS = "cors";
  private static final String EMPTY_DESTINATION = "empty";
  private static final String TLS_PASSWORD = "s9-test-only-password";
  private static final Path TLS_DIRECTORY = createTlsMaterial();
  private static final HttpClient HTTPS = trustedTestClient();

  @LocalServerPort private int port;
  @Autowired private MockMvc http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;
  @Autowired private PlatformTransactionManager transactions;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("hortinis.access.mode", () -> "single-user-auth");
    registry.add("hortinis.access.public-origin", () -> "https://localhost");
    registry.add("hortinis.access.tls-validated", () -> "true");
    registry.add("hortinis.operator.directory", OPERATOR_DIRECTORY::toString);
    registry.add("server.ssl.enabled", () -> "true");
    registry.add("server.ssl.key-store", () -> TLS_DIRECTORY.resolve("runtime.p12").toString());
    registry.add("server.ssl.key-store-password", () -> TLS_PASSWORD);
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-alias", () -> "hortinis-test");
    registry.add("server.forward-headers-strategy", () -> "none");
  }

  @AfterAll
  static void removeOperatorDirectory() throws IOException {
    if (Files.exists(OPERATOR_DIRECTORY)) {
      try (var files = Files.list(OPERATOR_DIRECTORY)) {
        for (Path file : files.toList()) {
          Files.delete(file);
        }
      }
      Files.delete(OPERATOR_DIRECTORY);
    }
    if (Files.exists(TLS_DIRECTORY)) {
      try (var files = Files.list(TLS_DIRECTORY)) {
        for (Path file : files.toList()) {
          Files.delete(file);
        }
      }
      Files.delete(TLS_DIRECTORY);
    }
  }

  @Test
  void runtimeClaimsOnlyFreshAuthModeAndSelectsTheAuthenticatedResolver() {
    assertThat(
            jdbc.queryForObject(
                "SELECT access_mode FROM technical_server_installation WHERE singleton",
                String.class))
        .isEqualTo("single-user-auth");
    assertThat(context.getBean("authenticatedSynchronizationAccessResolver"))
        .isInstanceOf(AuthenticatedSynchronizationAccessResolver.class);
  }

  @Test
  void statusDoesNotAllocateCookieAndCsrfUsesStrictSecureCookie() throws Exception {
    var status = http.perform(browserGet("/api/v1/auth/session")).andReturn().getResponse();
    assertThat(status.getStatus()).isEqualTo(200);
    assertThat(status.getHeader("Cache-Control")).contains("no-store");
    assertThat(status.getContentAsString()).contains("\"state\":\"anonymous\"");
    assertThat(status.getHeader(HttpHeaders.SET_COOKIE)).isNull();

    var csrf = http.perform(browserGet("/api/v1/auth/csrf")).andReturn().getResponse();
    assertThat(csrf.getStatus()).isEqualTo(200);
    assertThat(csrf.getHeader("Cache-Control")).contains("no-store");
    assertThat(csrf.getHeader(HttpHeaders.SET_COOKIE))
        .contains("__Host-hortinis-session=")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/");
    assertThat(csrf.getContentAsString()).contains("X-CSRF-TOKEN");
  }

  @Test
  void unsafeAuthenticationRequestNeedsOriginAndHeaderCsrfWithoutCookieMutation() throws Exception {
    var response =
        http.perform(
                post("/api/v1/auth/logout")
                    .header("Origin", "https://hortinis.test")
                    .header(FETCH_SITE, SAME_ORIGIN)
                    .header(FETCH_MODE, CORS)
                    .header(FETCH_DEST, EMPTY_DESTINATION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"target\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"}"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'self'");
  }

  @Test
  void unauthenticatedSynchronizationCannotFallBackToNoAuth() throws Exception {
    var response = http.perform(get(BOOTSTRAP_PATH)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
  }

  @Test
  void realLoopbackHttpsAppliesTlsOriginCookiesAndHsts() throws Exception {
    HttpResponse<String> status =
        HTTPS.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/auth/session"))
                .header(FETCH_SITE, SAME_ORIGIN)
                .header(FETCH_MODE, CORS)
                .header(FETCH_DEST, EMPTY_DESTINATION)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(status.statusCode()).isEqualTo(200);
    assertThat(status.body()).contains("\"state\":\"anonymous\"");
    assertThat(status.headers().firstValue("Strict-Transport-Security"))
        .contains("max-age=31536000");
    assertThat(status.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();

    HttpResponse<String> csrf =
        HTTPS.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/auth/csrf"))
                .header(FETCH_SITE, SAME_ORIGIN)
                .header(FETCH_MODE, CORS)
                .header(FETCH_DEST, EMPTY_DESTINATION)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrf.statusCode()).isEqualTo(200);
    assertThat(csrf.headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow())
        .contains("__Host-hortinis-session=")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/");

    HttpResponse<String> denied =
        HTTPS.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/auth/logout"))
                .header("Origin", "https://localhost")
                .header(FETCH_SITE, SAME_ORIGIN)
                .header(FETCH_MODE, CORS)
                .header(FETCH_DEST, EMPTY_DESTINATION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(denied.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void springAuthorizesCurrentFullSessionsAndRejectsRevocationWithoutLeakingThePrincipal()
      throws Exception {
    verifySpringSessions(AuthenticationJdbcSessions.create(jdbc, transactions));
  }

  private <S extends org.springframework.session.Session> void verifySpringSessions(
      org.springframework.session.SessionRepository<S> sessions) throws Exception {
    UUID account = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    var session = sessions.createSession();
    session.setAttribute("hortinis.authority", new SessionAuthorityReference(reference));
    session.setMaxInactiveInterval(java.time.Duration.ofHours(12));
    String csrf = AuthenticationSecrets.randomValue();
    session.setAttribute(
        "hortinis.csrf",
        new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", csrf));
    Instant now = Instant.now();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "INSERT INTO authentication_account (account_id, server_instance_id, "
                      + "synchronization_scope_id, login_identifier, status, "
                      + "password_verifier, user_handle, created_at) "
                      + "SELECT ?, server_instance_id, synchronization_scope_id, "
                      + "'spring-test', 'active', "
                      + "'{argon2id}$argon2id$v=19$m=65536,t=3,p=1$test', ?, ? "
                      + "FROM technical_synchronization_scope "
                      + "WHERE technical_default",
                  account,
                  new byte[32],
                  Timestamp.from(now));
              jdbc.update(
                  "INSERT INTO authentication_session_authority "
                      + "(session_reference, session_verifier, "
                      + "logout_target, state, account_id, authority_epoch, created_at, "
                      + "expires_at, full_login_at, "
                      + "last_interaction_at, both_factors_at) "
                      + "VALUES (?, ?, ?, 'full', ?, 1, ?, ?, ?, ?, ?)",
                  reference,
                  AuthenticationSecrets.digest(session.getId()),
                  AuthenticationSecrets.randomValue(),
                  account,
                  Timestamp.from(now),
                  Timestamp.from(now.plusSeconds(43_200)),
                  Timestamp.from(now),
                  Timestamp.from(now),
                  Timestamp.from(now));
              sessions.save(session);
            });
    Cookie cookie = new Cookie("__Host-hortinis-session", session.getId());
    try {
      var response = http.perform(get(BOOTSTRAP_PATH).cookie(cookie)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentAsString()).contains(account.toString());
      assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
      String expectation =
          tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readTree(response.getContentAsString())
              .get("expectation")
              .stringValue();
      assertThat(
              http.perform(
                      browserPost("/api/v1/sync/bindings/confirm", cookie, csrf)
                          .content(
                              "{\"expectation\":\""
                                  + expectation
                                  + "\",\"expectedPopulation\":\"empty\"}"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
      assertThat(
              http.perform(
                      get("/api/v1/sync/changes")
                          .cookie(cookie)
                          .header("Sync-Expectation", expectation))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
      assertThat(
              http.perform(
                      browserPost("/api/v1/sync/operations", cookie, csrf)
                          .header("Sync-Expectation", expectation)
                          .content("{}"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(400);
      for (String partial : new String[] {"password", "recovery"}) {
        jdbc.update(
            "UPDATE authentication_session_authority SET state = ?, expires_at = ? "
                + "WHERE session_reference = ?",
            partial,
            Timestamp.from(now.plusSeconds(300)),
            reference);
        assertThat(
                http.perform(get(BOOTSTRAP_PATH).cookie(cookie))
                    .andReturn()
                    .getResponse()
                    .getStatus())
            .isEqualTo(401);
      }
      jdbc.update(
          "UPDATE authentication_session_authority SET state = 'full', expires_at = ? "
              + "WHERE session_reference = ?",
          Timestamp.from(now.plusSeconds(43_200)),
          reference);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      assertThat(http.perform(get(BOOTSTRAP_PATH)).andReturn().getResponse().getStatus())
          .isEqualTo(401);
      jdbc.update(
          "UPDATE authentication_account SET authority_epoch = authority_epoch + 1 "
              + "WHERE account_id = ?",
          account);
      assertThat(
              http.perform(get(BOOTSTRAP_PATH).cookie(cookie))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(401);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      assertThat(
              http.perform(get("/api/v1/server/capabilities"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
    } finally {
      sessions.deleteById(session.getId());
      jdbc.update("DELETE FROM technical_exchange_expectation WHERE account_id = ?", account);
      jdbc.update("DELETE FROM authentication_account WHERE account_id = ?", account);
    }
  }

  private static MockHttpServletRequestBuilder browserPost(
      String path, Cookie cookie, String csrf) {
    return post(path)
        .cookie(cookie)
        .header("Origin", "https://localhost")
        .header(FETCH_SITE, SAME_ORIGIN)
        .header(FETCH_MODE, CORS)
        .header(FETCH_DEST, EMPTY_DESTINATION)
        .header("X-CSRF-TOKEN", csrf)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private MockHttpServletRequestBuilder browserGet(String path) {
    return get(path)
        .header(FETCH_SITE, SAME_ORIGIN)
        .header(FETCH_MODE, CORS)
        .header(FETCH_DEST, EMPTY_DESTINATION);
  }

  private static Path createOperatorDirectory() {
    try {
      Path directory = Files.createTempDirectory("hortinis-auth-runtime-");
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      return directory;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static Path createTlsMaterial() {
    try {
      Path directory = Files.createTempDirectory("hortinis-auth-tls-");
      Path keyStore = directory.resolve("runtime.p12");
      Process process =
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
                  keyStore.toString(),
                  "-storepass",
                  TLS_PASSWORD,
                  "-keypass",
                  TLS_PASSWORD,
                  "-noprompt")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (process.waitFor() != 0) {
        throw new IllegalStateException("Test TLS setup failed.");
      }
      return directory;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ExceptionInInitializerError(exception);
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static HttpClient trustedTestClient() {
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var stream = Files.newInputStream(TLS_DIRECTORY.resolve("runtime.p12"))) {
        keyStore.load(stream, TLS_PASSWORD.toCharArray());
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keyStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return HttpClient.newBuilder().sslContext(context).build();
    } catch (GeneralSecurityException | IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
