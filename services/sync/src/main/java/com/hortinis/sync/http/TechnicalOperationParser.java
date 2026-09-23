package com.hortinis.sync.http;

import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.protocol.UuidRules;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class TechnicalOperationParser {

  private static final String KIND = "kind";
  private static final String OPERATION_ID = "operationId";
  private static final String RECORD_ID = "recordId";
  private static final String VALUE = "value";
  private static final String EXPECTED_REVISION = "expectedRevision";

  private TechnicalOperationParser() {}

  static TechnicalRecordOperation parse(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new InvalidRequestException();
    }
    String kind = text(body, KIND);
    String operationId = text(body, OPERATION_ID);
    String recordId = text(body, RECORD_ID);
    if (!UuidRules.isCanonicalUuid(operationId) || !UuidRules.isCanonicalUuid(recordId)) {
      throw new InvalidRequestException();
    }
    String value = text(body, VALUE);
    if ("create".equals(kind)
        && value != null
        && fields(body).equals(Set.of(KIND, OPERATION_ID, RECORD_ID, VALUE))) {
      return new CreateTechnicalRecordOperation(operationId, recordId, value);
    }
    String expectedRevision = text(body, EXPECTED_REVISION);
    if ("replace".equals(kind)
        && value != null
        && fields(body).equals(Set.of(EXPECTED_REVISION, KIND, OPERATION_ID, RECORD_ID, VALUE))
        && UuidRules.isPositiveDecimal(expectedRevision)) {
      return new ReplaceTechnicalRecordOperation(operationId, recordId, value, expectedRevision);
    }
    String deleteExpectedRevision = text(body, EXPECTED_REVISION);
    if ("delete".equals(kind)
        && fields(body).equals(Set.of(EXPECTED_REVISION, KIND, OPERATION_ID, RECORD_ID))
        && UuidRules.isPositiveDecimal(deleteExpectedRevision)) {
      return new DeleteTechnicalRecordOperation(operationId, recordId, deleteExpectedRevision);
    }
    throw new InvalidRequestException();
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static Set<String> fields(JsonNode body) {
    Set<String> fields = new HashSet<>();
    fields.addAll(body.propertyNames());
    return fields;
  }
}
