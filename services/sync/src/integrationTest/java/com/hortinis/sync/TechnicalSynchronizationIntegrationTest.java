package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class TechnicalSynchronizationIntegrationTest {

  private static final String POSTGRES_IMAGE = "postgres:18.0";
  private static final String OPERATIONS_PATH = "/api/v1/sync/operations";
  private static final String CHANGES_PATH = "/api/v1/sync/changes";
  private static final String TECHNICAL_RECORD_TABLE = "technical_record";
  private static final String ACCEPTED_OPERATION_TABLE = "accepted_technical_record_operation";
  private static final String CHANGE_TABLE = "technical_record_change";
  private static final String FIRST_VALUE = "first value";
  private static final String REPLACEMENT_VALUE = "replacement value";
  private static final String VALUE_FIELD = "\",\"value\":\"";
  private static final String CLOSE_OBJECT = "}";
  private static final String CLOSE_QUOTE_AND_OBJECT = "\"}";
  private static final String OPERATION_ID = "01890f3e-7c5a-7b12-8abc-0123456789ab";
  private static final String RECORD_ID = "01890f3e-7c5a-7b13-8abc-0123456789ab";
  private static final String REPLACE_OPERATION_ID = "01890f3e-7c5a-7b14-8abc-0123456789ab";
  private static final String NON_V7_OPERATION_ID = "01890f3e-7c5a-4b12-8abc-0123456789ab";
  private static final String NON_V7_RECORD_ID = "01890f3e-7c5a-4b13-8abc-0123456789ab";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void clearApplicationData() {
    jdbc.update("DROP TRIGGER IF EXISTS fail_technical_change ON technical_record_change");
    jdbc.update("DROP FUNCTION IF EXISTS fail_technical_change()");
    jdbc.update(
        "TRUNCATE technical_record_change, accepted_technical_record_operation, "
            + "technical_record RESTART IDENTITY CASCADE");
  }

  @Test
  void acceptsCreateAndPersistsTheCompleteAcceptanceBundle() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk())
        .andExpect(
            content().json(acceptedResponse(OPERATION_ID, RECORD_ID, "1", FIRST_VALUE, "1"), true));

    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(1);
    assertThat(count(CHANGE_TABLE)).isEqualTo(1);
  }

  @Test
  void identicallyRetriesCreateWithStableResultAndNoDuplicateEffects() throws Exception {
    String firstResponse =
        mockMvc
            .perform(
                post(OPERATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequest(FIRST_VALUE)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String replayResponse =
        mockMvc
            .perform(
                post(OPERATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{ \"value\": \""
                            + FIRST_VALUE
                            + "\", \"kind\": \"create\", "
                            + "\"recordId\": \""
                            + RECORD_ID
                            + "\", \"operationId\": \""
                            + OPERATION_ID
                            + "\" }"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(replayResponse).isEqualTo(firstResponse);
    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(1);
    assertThat(count(CHANGE_TABLE)).isEqualTo(1);
  }

  @Test
  void identicallyRetriesReplaceWithStableResultAndNoAdditionalRevision() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk());

    String firstResponse =
        mockMvc
            .perform(
                post(OPERATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(replaceRequest(REPLACEMENT_VALUE, "1")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String replayResponse =
        mockMvc
            .perform(
                post(OPERATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"expectedRevision\":\"1\",\"kind\":\"replace\","
                            + "\"value\":\""
                            + REPLACEMENT_VALUE
                            + "\",\"recordId\":\""
                            + RECORD_ID
                            + "\",\"operationId\":\""
                            + REPLACE_OPERATION_ID
                            + CLOSE_QUOTE_AND_OBJECT))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(replayResponse).isEqualTo(firstResponse);
    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(2);
    assertThat(count(CHANGE_TABLE)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT revision FROM technical_record WHERE record_id = ?",
                Long.class,
                java.util.UUID.fromString(RECORD_ID)))
        .isEqualTo(2);
  }

  @Test
  void rejectsValidatedReuseWithoutChangingTheOriginalAcceptance() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("different value")))
        .andExpect(status().isConflict())
        .andExpect(
            content()
                .json(
                    "{\"code\":\"OPERATION_ID_REUSED\",\"message\":"
                        + "\"The operation identifier was reused.\",\"operationId\":\""
                        + OPERATION_ID
                        + CLOSE_QUOTE_AND_OBJECT));

    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(1);
    assertThat(count(CHANGE_TABLE)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT value FROM technical_record WHERE record_id = ?",
                String.class,
                java.util.UUID.fromString(RECORD_ID)))
        .isEqualTo(FIRST_VALUE);
  }

  @Test
  void concurrentIdenticalSubmissionsProduceOneStableAcceptance() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<String> first = executor.submit(() -> submitCreateAndReadResponse());
      Future<String> second = executor.submit(() -> submitCreateAndReadResponse());

      assertThat(first.get()).isEqualTo(second.get());
    }

    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(1);
    assertThat(count(CHANGE_TABLE)).isEqualTo(1);
  }

  @Test
  void acceptsReplaceAtTheCurrentRevision() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceRequest(REPLACEMENT_VALUE, "1")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    acceptedResponse(REPLACE_OPERATION_ID, RECORD_ID, "2", REPLACEMENT_VALUE, "2"),
                    true));

    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(2);
    assertThat(count(CHANGE_TABLE)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT revision FROM technical_record WHERE record_id = ?",
                Long.class,
                java.util.UUID.fromString(RECORD_ID)))
        .isEqualTo(2);
  }

  @Test
  void pullsAcceptedChangesInSequenceOrderAndResumesAfterAnOpaqueCursor() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceRequest(REPLACEMENT_VALUE, "1")))
        .andExpect(status().isOk());

    mockMvc
        .perform(get(CHANGES_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"changes\":["
                        + acceptedChange(RECORD_ID, OPERATION_ID, "1", FIRST_VALUE, "1")
                        + ","
                        + acceptedChange(
                            RECORD_ID, REPLACE_OPERATION_ID, "2", REPLACEMENT_VALUE, "2")
                        + "],\"nextCursor\":\"v1.Mg\",\"hasMore\":false}",
                    true));

    mockMvc
        .perform(get(CHANGES_PATH).param("cursor", "v1.MQ"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"changes\":["
                        + acceptedChange(
                            RECORD_ID, REPLACE_OPERATION_ID, "2", REPLACEMENT_VALUE, "2")
                        + "],\"nextCursor\":\"v1.Mg\",\"hasMore\":false}",
                    true));
  }

  @Test
  void rejectsMalformedPullCursors() throws Exception {
    mockMvc
        .perform(get(CHANGES_PATH).param("cursor", "not-a-cursor"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", false));
  }

  @Test
  void indicatesAnotherPageWithoutAdvancingBeyondTheReturnedChanges() throws Exception {
    for (int index = 0; index < 101; index++) {
      mockMvc
          .perform(
              post(OPERATIONS_PATH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      createRequest(
                          UUID.randomUUID().toString(),
                          UUID.randomUUID().toString(),
                          "value-" + index)))
          .andExpect(status().isOk());
    }

    mockMvc
        .perform(get(CHANGES_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(100))
        .andExpect(jsonPath("$.changes[0].sequence").value("1"))
        .andExpect(jsonPath("$.changes[99].sequence").value("100"))
        .andExpect(jsonPath("$.nextCursor").value("v1.MTAw"))
        .andExpect(jsonPath("$.hasMore").value(true));

    mockMvc
        .perform(get(CHANGES_PATH).param("cursor", "v1.MTAw"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].sequence").value("101"))
        .andExpect(jsonPath("$.nextCursor").value("v1.MTAx"))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void acceptsCanonicalUuidRegardlessOfVersion() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(NON_V7_OPERATION_ID, NON_V7_RECORD_ID, "non-v7 identifier")))
        .andExpect(status().isOk());

    assertThat(count(TECHNICAL_RECORD_TABLE)).isEqualTo(1);
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isEqualTo(1);
  }

  @Test
  void rollsBackEveryAcceptanceWriteWhenTheJournalFails() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION fail_technical_change() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''forced journal failure''; END;' ");
    jdbc.execute(
        "CREATE TRIGGER fail_technical_change BEFORE INSERT ON technical_record_change "
            + "FOR EACH ROW EXECUTE FUNCTION fail_technical_change()");

    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("rolled back")))
        .andExpect(status().is5xxServerError());

    assertThat(count(TECHNICAL_RECORD_TABLE)).isZero();
    assertThat(count(ACCEPTED_OPERATION_TABLE)).isZero();
    assertThat(count(CHANGE_TABLE)).isZero();
  }

  @Test
  void rejectsNonCanonicalAndUnknownOperationShapes() throws Exception {
    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"operationId\":\"01890f3e-7c5a-7b12-8abc-0123456789ab\","
                        + "\"recordId\":\"01890F3E-7C5A-7B13-8ABC-0123456789AB\","
                        + "\"value\":\"invalid\",\"kind\":\"create\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", false));

    mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequestWithExtraField()))
        .andExpect(status().isBadRequest());
  }

  private String createRequest(String value) {
    return createRequest(OPERATION_ID, RECORD_ID, value);
  }

  private String createRequest(String operationId, String recordId, String value) {
    return "{\"operationId\":\""
        + operationId
        + "\",\"recordId\":\""
        + recordId
        + VALUE_FIELD
        + value
        + "\",\"kind\":\"create\""
        + CLOSE_OBJECT;
  }

  private String createRequestWithExtraField() {
    return createRequest("invalid").replace("}", ",\"extra\":true}");
  }

  private String submitCreateAndReadResponse() throws Exception {
    return mockMvc
        .perform(
            post(OPERATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest(FIRST_VALUE)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String replaceRequest(String value, String expectedRevision) {
    return "{\"operationId\":\""
        + REPLACE_OPERATION_ID
        + "\",\"recordId\":\""
        + RECORD_ID
        + VALUE_FIELD
        + value
        + "\",\"kind\":\"replace\",\"expectedRevision\":\""
        + expectedRevision
        + CLOSE_QUOTE_AND_OBJECT;
  }

  private String acceptedResponse(
      String operationId, String recordId, String revision, String value, String sequence) {
    return "{\"outcome\":\"accepted\",\"operationId\":\""
        + operationId
        + "\",\"record\":{\"recordId\":\""
        + recordId
        + "\",\"revision\":\""
        + revision
        + VALUE_FIELD
        + value
        + "\"},\"sequence\":\""
        + sequence
        + CLOSE_QUOTE_AND_OBJECT;
  }

  private String acceptedChange(
      String recordId, String operationId, String revision, String value, String sequence) {
    return "{\"operationId\":\""
        + operationId
        + "\",\"record\":{\"recordId\":\""
        + recordId
        + "\",\"revision\":\""
        + revision
        + VALUE_FIELD
        + value
        + "\"},\"sequence\":\""
        + sequence
        + "\"}";
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
