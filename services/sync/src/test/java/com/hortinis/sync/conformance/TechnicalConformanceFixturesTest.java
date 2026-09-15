package com.hortinis.sync.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TechnicalConformanceFixturesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern UUID_V7 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");
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
          "replace-accepted.json");

  @Test
  void consumesEverySharedScenarioAndAgreesWithExpectedValidity() throws IOException {
    for (String fixtureName : FIXTURES) {
      JsonNode fixture = readFixture(fixtureName);
      JsonNode request = fixture.get("request");
      JsonNode expected = fixture.get("expected");
      boolean expectedValid = expected.get("valid").booleanValue();

      if (isObject(request) && request.has("kind")) {
        assertThat(isTechnicalRecordOperation(request)).isEqualTo(expectedValid);
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
      if (errorCode != null) {
        assertThat(expectedResponse.get("code").textValue()).isEqualTo(errorCode.textValue());
      }
    }
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
        TechnicalConformanceFixturesTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(stream).as("fixture %s", name).isNotNull();
      return JSON.readTree(stream);
    }
  }

  private static boolean isTechnicalRecordOperation(JsonNode value) {
    if (!isObject(value)
        || !value.get("operationId").isTextual()
        || !UUID_V7.matcher(value.get("operationId").textValue()).matches()
        || !value.get("recordId").isTextual()
        || !UUID_V7.matcher(value.get("recordId").textValue()).matches()
        || !value.get("value").isTextual()
        || !value.get("kind").isTextual()) {
      return false;
    }

    Set<String> keys = fieldNames(value);
    if ("create".equals(value.get("kind").textValue())) {
      return keys.equals(Set.of("kind", "operationId", "recordId", "value"));
    }
    return "replace".equals(value.get("kind").textValue())
        && keys.equals(Set.of("expectedRevision", "kind", "operationId", "recordId", "value"))
        && value.get("expectedRevision").isTextual()
        && POSITIVE_DECIMAL.matcher(value.get("expectedRevision").textValue()).matches();
  }

  private static boolean operationsEqual(JsonNode left, JsonNode right) {
    return isTechnicalRecordOperation(left)
        && isTechnicalRecordOperation(right)
        && left.get("operationId").textValue().equals(right.get("operationId").textValue())
        && left.get("recordId").textValue().equals(right.get("recordId").textValue())
        && left.get("value").textValue().equals(right.get("value").textValue())
        && left.get("kind").textValue().equals(right.get("kind").textValue())
        && java.util.Objects.equals(left.get("expectedRevision"), right.get("expectedRevision"));
  }

  private static boolean isObject(JsonNode value) {
    return value != null && value.isObject();
  }

  private static Set<String> fieldNames(JsonNode value) {
    Set<String> names = new HashSet<>();
    names.addAll(value.propertyNames());
    return names;
  }
}
