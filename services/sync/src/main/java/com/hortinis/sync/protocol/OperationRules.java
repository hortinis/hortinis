package com.hortinis.sync.protocol;

import java.util.Objects;

public final class OperationRules {

  private OperationRules() {}

  public static void validate(TechnicalRecordOperation operation) {
    if (operation == null
        || !UuidRules.isCanonicalUuid(operation.operationId())
        || !UuidRules.isCanonicalUuid(operation.recordId())
        || operation.value() == null) {
      throw new IllegalArgumentException("The operation is invalid.");
    }
    if (operation instanceof CreateTechnicalRecordOperation) {
      return;
    }
    if (operation instanceof ReplaceTechnicalRecordOperation replace
        && UuidRules.isPositiveDecimal(replace.expectedRevision())) {
      return;
    }
    throw new IllegalArgumentException("The operation is invalid.");
  }

  public static boolean equal(TechnicalRecordOperation left, TechnicalRecordOperation right) {
    if (left == null || right == null || !left.kind().equals(right.kind())) {
      return false;
    }
    if (!Objects.equals(left.operationId(), right.operationId())
        || !Objects.equals(left.recordId(), right.recordId())
        || !Objects.equals(left.value(), right.value())) {
      return false;
    }
    if (left instanceof ReplaceTechnicalRecordOperation leftReplace
        && right instanceof ReplaceTechnicalRecordOperation rightReplace) {
      return Objects.equals(leftReplace.expectedRevision(), rightReplace.expectedRevision());
    }
    return left instanceof CreateTechnicalRecordOperation
        && right instanceof CreateTechnicalRecordOperation;
  }
}
