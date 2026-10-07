package com.hortinis.sync.http;

import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.RecordId;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
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
    if (kind == null) {
      throw new InvalidRequestException();
    }
    try {
      OperationId operationId = OperationId.parse(text(body, OPERATION_ID));
      RecordId recordId = RecordId.parse(text(body, RECORD_ID));
      return switch (kind) {
        case "create" -> {
          requireFields(body, Set.of(KIND, OPERATION_ID, RECORD_ID, VALUE));
          yield new CreateTechnicalRecordOperation(operationId, recordId, text(body, VALUE));
        }
        case "replace" -> {
          requireFields(body, Set.of(EXPECTED_REVISION, KIND, OPERATION_ID, RECORD_ID, VALUE));
          yield new ReplaceTechnicalRecordOperation(
              operationId,
              recordId,
              text(body, VALUE),
              ExpectedRevision.parse(text(body, EXPECTED_REVISION)));
        }
        case "delete" -> {
          requireFields(body, Set.of(EXPECTED_REVISION, KIND, OPERATION_ID, RECORD_ID));
          yield new DeleteTechnicalRecordOperation(
              operationId, recordId, ExpectedRevision.parse(text(body, EXPECTED_REVISION)));
        }
        default -> throw new InvalidRequestException();
      };
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException();
    }
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static void requireFields(JsonNode body, Set<String> expected) {
    if (!new HashSet<>(body.propertyNames()).equals(expected)) {
      throw new InvalidRequestException();
    }
  }
}
