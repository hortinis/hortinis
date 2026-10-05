package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class TechnicalInputHttpIntegrationTest {

  private static final String SENTINEL = "private-input-h2-sentinel";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String OPERATIONS = "/api/v1/sync/operations";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void clearApplicationData() {
    jdbc.update(
        "TRUNCATE technical_record_change, accepted_technical_record_operation, technical_record "
            + "RESTART IDENTITY CASCADE");
  }

  @Test
  void rejectsKnownAndChunkedOversizedBodiesIncludingTrailingWhitespace(CapturedOutput output)
      throws Exception {
    String valid = request(SENTINEL);
    for (String body : new String[] {" ".repeat(65_536) + valid, valid + " ".repeat(65_536)}) {
      assertRejected(send(HttpRequest.BodyPublishers.ofString(body)));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      HttpRequest.BodyPublisher chunked =
          HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
      assertThat(chunked.contentLength()).isEqualTo(-1);
      assertRejected(send(chunked));
      assertRejected(send(chunked, "/api/v1/sync/%6fperations"));
    }
    assertNoAcceptance();
    assertThat(output.getAll()).doesNotContain(SENTINEL);
  }

  @Test
  void acceptsAnExactlyBoundedBodyAndReplaysAnUnchangedValue() throws Exception {
    String value = "🌱".repeat(4096);
    String body = request(value);
    int padding = 65_536 - body.getBytes(StandardCharsets.UTF_8).length;
    HttpResponse<String> accepted =
        send(HttpRequest.BodyPublishers.ofString(body + " ".repeat(padding)));
    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(accepted.body()).path("record").path("value").textValue())
        .isEqualTo(value);
    HttpResponse<String> replay = send(HttpRequest.BodyPublishers.ofString(body));
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(accepted.body());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM technical_record_change", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectsMalformedAndUnstorableInputWithoutLeakingContent(CapturedOutput output)
      throws Exception {
    for (String value :
        new String[] {
          SENTINEL + "\u0000",
          SENTINEL + String.valueOf(Character.MIN_HIGH_SURROGATE),
          SENTINEL + String.valueOf(Character.MIN_LOW_SURROGATE),
          SENTINEL + "x".repeat(4096)
        }) {
      assertRejected(send(HttpRequest.BodyPublishers.ofString(request(value))));
    }
    assertRejected(send(HttpRequest.BodyPublishers.ofString("{\"value\":\"" + SENTINEL)));
    assertNoAcceptance();
    assertThat(output.getAll()).doesNotContain(SENTINEL);
  }

  private HttpResponse<String> send(HttpRequest.BodyPublisher body) throws Exception {
    return send(body, OPERATIONS);
  }

  private HttpResponse<String> send(HttpRequest.BodyPublisher body, String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Content-Type", "application/json")
              .POST(body)
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String request(String value) {
    return JSON.writeValueAsString(
            Map.of(
                "kind",
                "create",
                "operationId",
                UUID.randomUUID().toString(),
                "recordId",
                UUID.randomUUID().toString(),
                "value",
                value))
        .replace(String.valueOf(Character.MIN_HIGH_SURROGATE), "\\ud800")
        .replace(String.valueOf(Character.MIN_LOW_SURROGATE), "\\udc00");
  }

  private static void assertRejected(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(JSON.readTree(response.body()).path("code").textValue())
        .isEqualTo("INVALID_REQUEST");
    assertThat(JSON.readTree(response.body()).path("message").textValue())
        .isEqualTo("The request is invalid.");
    assertThat(response.body()).doesNotContain(SENTINEL);
  }

  private void assertNoAcceptance() {
    for (String table :
        new String[] {
          "technical_record", "accepted_technical_record_operation", "technical_record_change"
        }) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }
  }
}
