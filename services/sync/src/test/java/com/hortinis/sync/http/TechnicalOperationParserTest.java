package com.hortinis.sync.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.protocol.InvalidRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TechnicalOperationParserTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest
  @ValueSource(strings = {"create", "replace", "delete"})
  void rejectsConstructionFailuresAsInvalidRequests(String kind) {
    ObjectNode valid = request(kind);
    assertThat(TechnicalOperationParser.parse(valid).kind()).isEqualTo(kind);
    ObjectNode invalidId = valid.deepCopy();
    invalidId.put("operationId", "invalid");
    assertInvalid(invalidId);
    ObjectNode missingId = valid.deepCopy();
    missingId.remove("recordId");
    assertInvalid(missingId);
    ObjectNode extraField = valid.deepCopy();
    extraField.put("extra", "unexpected");
    assertInvalid(extraField);
    ObjectNode wrongType = valid.deepCopy();
    wrongType.put("recordId", 1);
    assertInvalid(wrongType);
    if (!"create".equals(kind)) {
      ObjectNode invalidRevision = valid.deepCopy();
      invalidRevision.put("expectedRevision", "01");
      assertInvalid(invalidRevision);
      ObjectNode numericRevision = valid.deepCopy();
      numericRevision.put("expectedRevision", 1);
      assertInvalid(numericRevision);
    }
    if (!"delete".equals(kind)) {
      ObjectNode invalidValue = valid.deepCopy();
      invalidValue.put("value", "\u0000");
      assertInvalid(invalidValue);
      ObjectNode nullValue = valid.deepCopy();
      nullValue.putNull("value");
      assertInvalid(nullValue);
    }
  }

  private static ObjectNode request(String kind) {
    ObjectNode body = JSON.createObjectNode();
    body.put("kind", kind);
    body.put("operationId", "01890f3e-7c5a-7b12-8abc-0123456789ab");
    body.put("recordId", "01890f3e-7c5a-7b13-8abc-0123456789ab");
    if (!"delete".equals(kind)) {
      body.put("value", "");
    }
    if (!"create".equals(kind)) {
      body.put("expectedRevision", "1");
    }
    return body;
  }

  private static void assertInvalid(ObjectNode body) {
    assertThatThrownBy(() -> TechnicalOperationParser.parse(body))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("The request is invalid.");
  }
}
