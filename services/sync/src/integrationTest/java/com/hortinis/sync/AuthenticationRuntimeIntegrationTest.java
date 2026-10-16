package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAuthorityReference;
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
import org.springframework.security.core.userdetails.User;
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
  private static final String IDLE_CASE = "idle";
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
    assertThat(denied.headers().firstValue(HttpHeaders.SET_COOKIE).orElseThrow())
        .contains("__Host-hortinis-session=", "Secure", "HttpOnly", "SameSite=Strict");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"epoch", IDLE_CASE, "password", "recovery", "factors"})
  void springAuthorizesCurrentFullSessionsAndRejectsExpiryOrRevocation(String invalidation)
      throws Exception {
    verifySpringSessions(
        context.getBean(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class),
        invalidation);
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void nativeSchedulerDoesNotLogDatabaseExceptionDetails(
      org.springframework.boot.test.system.CapturedOutput output) {
    org.springframework.scheduling.support.TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(
        new org.springframework.dao.DataAccessResourceFailureException(
            "synthetic-native-cleanup-private-detail"));
    assertThat(output).doesNotContain("synthetic-native-cleanup-private-detail");
  }

  @Test
  void bootSessionWritesRollbackWithThePublicationTransaction() {
    verifySessionRollback(
        context.getBean(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class));
  }

  private <S extends org.springframework.session.Session> void verifySessionRollback(
      org.springframework.session.SessionRepository<S> sessions) {
    String identifier =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  var session = sessions.createSession();
                  session.setAttribute(
                      "hortinis.authority", new SessionAuthorityReference(UUID.randomUUID()));
                  sessions.save(session);
                  status.setRollbackOnly();
                  return session.getId();
                });
    assertThat(sessions.findById(identifier)).isNull();
    assertThat(context.containsBean("springSessionRepositoryFilter")).isTrue();
  }

  private <S extends org.springframework.session.Session> void verifySpringSessions(
      org.springframework.session.SessionRepository<S> sessions, String invalidation)
      throws Exception {
    final UUID account = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    var session = sessions.createSession();
    session.setAttribute("hortinis.authority", new SessionAuthorityReference(reference));
    String csrf = AuthenticationSecrets.randomValue();
    session.setAttribute(
        "hortinis.csrf",
        new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", csrf));
    Instant now = Instant.now();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var security =
                  org.springframework.security.core.context.SecurityContextHolder
                      .createEmptyContext();
              security.setAuthentication(AuthenticationIdentity.completed(account, 1, now));
              session.setAttribute(
                  org.springframework.security.web.context.HttpSessionSecurityContextRepository
                      .SPRING_SECURITY_CONTEXT_KEY,
                  security);
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
              session.setAttribute(
                  com.hortinis.sync.authentication.AuthenticationSessionTransport
                      .LOGOUT_TARGET_ATTRIBUTE,
                  AuthenticationSecrets.randomValue());
              sessions.save(session);
            });
    Instant previousAccess = now.minusSeconds(1200);
    session.setLastAccessedTime(previousAccess);
    sessions.save(session);
    Cookie cookie = new Cookie("__Host-hortinis-session", session.getId());
    try {
      var response = http.perform(get(BOOTSTRAP_PATH).cookie(cookie)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentAsString()).contains(account.toString());
      assertThat(sessions.findById(session.getId()).getLastAccessedTime()).isAfter(previousAccess);
      assertThat(sessions.findById(session.getId()).getMaxInactiveInterval())
          .isEqualTo(java.time.Duration.ofMinutes(30));
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
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      assertThat(http.perform(get(BOOTSTRAP_PATH)).andReturn().getResponse().getStatus())
          .isEqualTo(401);
      assertThat(
              http.perform(get(BOOTSTRAP_PATH).cookie(cookie))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
      switch (invalidation) {
        case IDLE_CASE ->
            jdbc.update(
                "UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE "
                    + "session_id = ?",
                session.getId());
        case "epoch" ->
            jdbc.update(
                "UPDATE authentication_account SET authority_epoch = authority_epoch + 1 "
                    + "WHERE account_id = ?",
                account);
        case "factors" -> {
          var value = sessions.findById(session.getId());
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              AuthenticationIdentity.completed(account, 1, now.minusSeconds(43200)));
          value.setAttribute(
              org.springframework.security.web.context.HttpSessionSecurityContextRepository
                  .SPRING_SECURITY_CONTEXT_KEY,
              context);
          sessions.save(value);
        }
        case "password" -> {
          var value = sessions.findById(session.getId());
          var completed = AuthenticationIdentity.completed(account, 1, now);
          var authorities =
              completed.getAuthorities().stream()
                  .filter(
                      authority ->
                          !org.springframework.security.core.authority.FactorGrantedAuthority
                              .WEBAUTHN_AUTHORITY
                              .equals(authority.getAuthority()))
                  .toList();
          var user = new User(account.toString(), "", authorities);
          user.eraseCredentials();
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                  .authenticated(user, null, authorities));
          value.setAttribute(
              org.springframework.security.web.context.HttpSessionSecurityContextRepository
                  .SPRING_SECURITY_CONTEXT_KEY,
              context);
          sessions.save(value);
        }
        case "recovery" -> {
          var value = sessions.findById(session.getId());
          value.removeAttribute(
              org.springframework.security.web.context.HttpSessionSecurityContextRepository
                  .SPRING_SECURITY_CONTEXT_KEY);
          sessions.save(value);
        }
        default -> throw new AssertionError("Unknown native session fixture.");
      }
      assertThat(
              http.perform(get(BOOTSTRAP_PATH).cookie(cookie))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(401);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      if (IDLE_CASE.equals(invalidation)) {
        assertThat(sessions.findById(session.getId())).isNull();
      } else {
        assertThat(sessions.findById(session.getId())).isNotNull();
      }
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
