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
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class TransientFailureMappingTest {

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
  }

  @ParameterizedTest
  @MethodSource("permanentFailures")
  void retainsUnexpectedFailuresAsEmpty500(RuntimeException failure) throws Exception {
    mvc(failure)
        .perform(post(OPERATIONS))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(""))
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void preservesInvalidRequestClassification() throws Exception {
    mvc(new com.hortinis.sync.protocol.InvalidRequestException())
        .perform(post(OPERATIONS))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
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
