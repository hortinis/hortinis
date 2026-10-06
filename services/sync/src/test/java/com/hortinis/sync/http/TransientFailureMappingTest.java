package com.hortinis.sync.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hortinis.sync.observability.RequestLoggingFilter;
import java.io.InputStream;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "hortinis.sync.persistence.enabled=false"
    })
class TransientFailureMappingTest {

  private static final String REQUEST_ID = "request_id";
  private static final String TRACE_ID = "trace_id";
  private static final String REQUEST_FAILED = "request_failed";
  private static final String SENTINEL = "private-database-error-value";
  private static final String OPERATIONS = "/api/v1/sync/operations";
  private static final String CHANGES = "/api/v1/sync/changes";

  @ParameterizedTest
  @MethodSource("unavailableFailures")
  void mapsRecognizedFailuresOnBothRoutes(RuntimeException failure, CapturedOutput output)
      throws Exception {
    JsonNode fixture;
    try (InputStream stream =
        Objects.requireNonNull(
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("synchronization-unavailable.json"))) {
      fixture = JsonMapper.builder().build().readTree(stream);
    }
    MockMvc mvc = mvc(failure);
    for (String path : new String[] {OPERATIONS, CHANGES}) {
      mvc.perform(OPERATIONS.equals(path) ? post(path) : get(path))
          .andExpect(status().is(fixture.path("status").intValue()))
          .andExpect(content().json(fixture.path("value").toString(), true))
          .andExpect(
              header()
                  .string(
                      HttpHeaders.RETRY_AFTER,
                      fixture.path("headers").path(HttpHeaders.RETRY_AFTER).textValue()))
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    assertThat(output.getAll()).doesNotContain(SENTINEL);
    assertThat(events(output, REQUEST_FAILED)).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("permanentFailures")
  void logsUnexpectedFailuresOnceWithoutSensitiveContent(
      RuntimeException failure, CapturedOutput output) throws Exception {
    MockMvc mvc = mvc(failure);
    for (String path : new String[] {OPERATIONS, CHANGES}) {
      mvc.perform(OPERATIONS.equals(path) ? post(path) : get(path))
          .andExpect(status().isInternalServerError())
          .andExpect(content().string(""))
          .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }
    var failures = events(output, REQUEST_FAILED);
    var completions = events(output, "request_completed");
    assertThat(failures).hasSize(2);
    assertThat(completions).hasSize(2);
    for (int index = 0; index < failures.size(); index++) {
      JsonNode event = failures.get(index);
      JsonNode completion = completions.get(index);
      assertThat(event.path("level").textValue()).isEqualTo("ERROR");
      assertThat(event.path("message").textValue()).isEqualTo("Request failed");
      assertThat(event.path("exception_class").textValue()).isEqualTo(failure.getClass().getName());
      assertThat(event.path(REQUEST_ID).textValue()).isNotBlank();
      assertThat(event.path(REQUEST_ID)).isEqualTo(completion.path(REQUEST_ID));
      assertThat(event.path(TRACE_ID).textValue()).isNotBlank();
      assertThat(event.path(TRACE_ID)).isEqualTo(completion.path(TRACE_ID));
      assertThat(event.propertyNames())
          .containsOnly(
              "@timestamp",
              "@version",
              "message",
              "logger_name",
              "thread_name",
              "level",
              "level_value",
              REQUEST_ID,
              TRACE_ID,
              "event",
              "exception_class");
      assertThat(completion.path("status").intValue()).isEqualTo(500);
      assertThat(completion.path("route").textValue()).isEqualTo(index == 0 ? OPERATIONS : CHANGES);
    }
    assertThat(failures.get(0).path(REQUEST_ID)).isNotEqualTo(failures.get(1).path(REQUEST_ID));
    assertThat(output.getAll()).doesNotContain(SENTINEL);
    assertThat(MDC.get(REQUEST_ID)).isNull();
    assertThat(MDC.get(TRACE_ID)).isNull();
  }

  @Test
  void preservesInvalidRequestClassification(CapturedOutput output) throws Exception {
    mvc(new com.hortinis.sync.protocol.InvalidRequestException())
        .perform(post(OPERATIONS))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    assertThat(events(output, REQUEST_FAILED)).isEmpty();
  }

  @Test
  void controlledNotFoundAndConflictDoNotEmitFailureEvents(CapturedOutput output) throws Exception {
    mvc(new com.hortinis.sync.protocol.RecordNotFoundException(SENTINEL, SENTINEL))
        .perform(post(OPERATIONS))
        .andExpect(status().isNotFound());
    mvc(new com.hortinis.sync.protocol.OperationIdReusedException(SENTINEL))
        .perform(post(OPERATIONS))
        .andExpect(status().isConflict());
    assertThat(events(output, REQUEST_FAILED)).isEmpty();
    assertThat(output.getAll()).doesNotContain(SENTINEL);
  }

  private static List<JsonNode> events(CapturedOutput output, String name) {
    return output
        .getAll()
        .lines()
        .filter(line -> line.contains("\"event\":\"" + name + "\""))
        .map(line -> JsonMapper.builder().build().readTree(line))
        .toList();
  }

  private static Stream<RuntimeException> unavailableFailures() {
    return Stream.of(
        new QueryTimeoutException(SENTINEL),
        new CannotAcquireLockException(SENTINEL),
        new DataAccessResourceFailureException(SENTINEL),
        new CannotCreateTransactionException(
            SENTINEL, new SQLTransientConnectionException(SENTINEL)),
        new CannotCreateTransactionException(SENTINEL, new SQLException(SENTINEL, "08006")),
        new CannotCreateTransactionException(SENTINEL, new SQLException(SENTINEL, "57P01")),
        new CannotCreateTransactionException(
            SENTINEL, new DataAccessResourceFailureException(SENTINEL)));
  }

  private static Stream<RuntimeException> permanentFailures() {
    return Stream.of(
        new IllegalStateException(SENTINEL),
        new DataIntegrityViolationException(SENTINEL),
        new BadSqlGrammarException("query", SENTINEL, new SQLException(SENTINEL, "42601")),
        new CannotCreateTransactionException(SENTINEL),
        new CannotCreateTransactionException(SENTINEL, new IllegalStateException(SENTINEL)),
        new CannotCreateTransactionException(SENTINEL, new SQLException(SENTINEL, "28000")));
  }

  private static MockMvc mvc(RuntimeException failure) {
    return MockMvcBuilders.standaloneSetup(new FailureController(failure))
        .setControllerAdvice(new TechnicalSynchronizationErrorHandler())
        .addFilters(new RequestLoggingFilter())
        .build();
  }

  @RestController
  static class FailureController {
    private final RuntimeException failure;

    FailureController(RuntimeException failure) {
      this.failure = failure;
    }

    @RequestMapping({OPERATIONS, CHANGES})
    Object fail() {
      throw failure;
    }
  }
}
