package com.hortinis.sync.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationRules;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.RecordTechnicalChange;
import com.hortinis.sync.protocol.SyncCursorCodec;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.protocol.TombstoneOperationResult;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TechnicalConformanceFixturesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> FIXTURES =
      List.of(
          "change-page.json",
          "create-accepted.json",
          "invalid-change-cursor.json",
          "invalid-identifiers-and-revision.json",
          "invalid-operation-shape.json",
          "invalid-request.json",
          "operation-id-reused.json",
          "record-already-exists.json",
          "record-not-found.json",
          "replay-equivalent.json",
          "replace-accepted.json",
          "revision-conflict.json");

  @Test
  void consumesEverySharedScenarioAndAgreesWithExpectedValidity() throws IOException {
    for (String fixtureName : FIXTURES) {
      JsonNode fixture = readFixture(fixtureName);
      JsonNode request = fixture.get("request");
      JsonNode expected = fixture.get("expected");
      boolean expectedValid = expected.get("valid").booleanValue();

      if (isObject(request) && request.has("kind")) {
        assertThat(isValidOperation(request)).isEqualTo(expectedValid);
      } else if (isObject(request) && request.has("cursor")) {
        assertThat(isValidCursor(request.get("cursor").textValue())).isEqualTo(expectedValid);
      } else if (expected.get("errorCode") != null) {
        assertThat(expectedValid).isFalse();
      }
      JsonNode equivalentRequest = fixture.get("equivalentRequest");
      if (equivalentRequest != null) {
        assertThat(operationsEqual(request, equivalentRequest))
            .isEqualTo(expected.get("equivalent").booleanValue());
      }
      JsonNode state = fixture.get("state");
      JsonNode acceptedOperations = state == null ? null : state.get("acceptedOperations");
      if (acceptedOperations != null && isObject(request)) {
        for (JsonNode receipt : acceptedOperations) {
          if (request
              .get("operationId")
              .textValue()
              .equals(receipt.get("operationId").textValue())) {
            assertThat(operationsEqual(request, receipt.get("request"))).isFalse();
          }
        }
      }
      JsonNode errorCode = expected.get("errorCode");
      JsonNode expectedResponse = expected.get("response");
      String responseCode =
          expectedResponse != null && expectedResponse.has("code")
              ? expectedResponse.get("code").textValue()
              : null;
      if (errorCode != null || responseCode != null) {
        assertThat(responseCode)
            .isEqualTo(errorCode == null ? responseCode : errorCode.textValue());
        assertResponseShape(
            expectedResponse, errorCode == null ? responseCode : errorCode.textValue());
      } else if (expectedResponse != null) {
        assertThat(isObject(expectedResponse)).isTrue();
        assertOperationResult(expectedResponse);
      }
      JsonNode changePage = fixture.get("response");
      if (changePage != null) {
        assertChangePage(changePage);
      }
    }
  }

  @Test
  void consumesG2aDeletionResultChangeAndRetiredIdentifierFixtures() throws IOException {
    JsonNode deleteResult = readFixture("delete-accepted.json").get("value");
    assertThat(JSON.treeToValue(deleteResult, TombstoneOperationResult.class)).isNotNull();

    JsonNode page = readFixture("tombstone-change-page.json").get("value");
    assertChangePage(page);
    assertThat(JSON.treeToValue(page.get("changes").get(0), TombstoneTechnicalChange.class))
        .isNotNull();

    JsonNode retired = readFixture("record-identifier-retired.json").get("value");
    assertThat(
            JSON.treeToValue(
                retired, TechnicalSynchronizationErrorHandler.RecordIdentifierRetiredError.class))
        .isNotNull();
  }

  @Test
  void everyScenarioHasAnIdentifierAndDescription() throws IOException {
    for (String fixtureName : FIXTURES) {
      JsonNode fixture = readFixture(fixtureName);
      assertThat(fixture.get("id").textValue()).isEqualTo(fixtureName.replace(".json", ""));
      assertThat(fixture.get("description").textValue()).isNotBlank();
      assertThat(fixture.get("expected").isObject()).isTrue();
    }
  }

  private static JsonNode readFixture(String name) throws IOException {
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
      assertThat(stream).as("fixture %s", name).isNotNull();
      return JSON.readTree(stream);
    }
  }

  private static boolean isValidOperation(JsonNode value) {
    try {
      TechnicalRecordOperation operation = TechnicalOperationParser.parse(value);
      OperationRules.validate(operation);
      return true;
    } catch (InvalidRequestException | IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean operationsEqual(JsonNode left, JsonNode right) {
    try {
      return OperationRules.equal(
          TechnicalOperationParser.parse(left), TechnicalOperationParser.parse(right));
    } catch (InvalidRequestException | IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isObject(JsonNode value) {
    return value != null && value.isObject();
  }

  private static boolean isValidCursor(String cursor) {
    try {
      SyncCursorCodec.decode(cursor);
      return true;
    } catch (InvalidRequestException exception) {
      return false;
    }
  }

  private static void assertResponseShape(JsonNode response, String code) throws IOException {
    switch (code) {
      case "INVALID_REQUEST" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.InvalidRequestError.class);
      case "RECORD_NOT_FOUND" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.RecordNotFoundError.class);
      case "OPERATION_ID_REUSED" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.OperationIdReusedError.class);
      case "RECORD_ALREADY_EXISTS" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.RecordAlreadyExistsError.class);
      case "RECORD_IDENTIFIER_RETIRED" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.RecordIdentifierRetiredError.class);
      case "REVISION_CONFLICT" ->
          JSON.treeToValue(
              response, TechnicalSynchronizationErrorHandler.RevisionConflictError.class);
      default -> throw new AssertionError("Unknown fixture error code: " + code);
    }
  }

  private static void assertOperationResult(JsonNode value) throws IOException {
    if (value.has("record")) {
      assertThat(JSON.treeToValue(value, RecordOperationResult.class)).isNotNull();
    } else {
      assertThat(JSON.treeToValue(value, TombstoneOperationResult.class)).isNotNull();
    }
  }

  private static void assertChangePage(JsonNode value) throws IOException {
    assertThat(value.get("changes").isArray()).isTrue();
    for (JsonNode change : value.get("changes")) {
      if (change.has("record")) {
        assertThat(JSON.treeToValue(change, RecordTechnicalChange.class)).isNotNull();
      } else {
        assertThat(JSON.treeToValue(change, TombstoneTechnicalChange.class)).isNotNull();
      }
    }
    assertThat(value.get("nextCursor").isTextual()).isTrue();
    assertThat(value.get("hasMore").isBoolean()).isTrue();
  }
}
