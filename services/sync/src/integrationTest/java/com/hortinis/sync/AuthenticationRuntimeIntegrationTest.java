package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hortinis.sync.access.AuthenticatedSynchronizationAccessResolver;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
    var response = http.perform(get("/api/v1/sync/bootstrap")).andReturn().getResponse();
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
