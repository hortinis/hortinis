package com.hortinis.sync.protocol;

import java.util.Objects;

public final class OperationRules {

  private OperationRules() {}

  public static void validate(TechnicalRecordOperation operation) {
    if (operation == null
        || !UuidRules.isCanonicalUuid(operation.operationId())
        || !UuidRules.isCanonicalUuid(operation.recordId())) {
      throw new IllegalArgumentException("The operation is invalid.");
    }
    if (operation instanceof CreateTechnicalRecordOperation create && create.value() != null) {
      return;
    }
    if (operation instanceof ReplaceTechnicalRecordOperation replace
        && replace.value() != null
        && UuidRules.isPositiveDecimal(replace.expectedRevision())) {
      return;
    }
    if (operation instanceof DeleteTechnicalRecordOperation delete
        && UuidRules.isPositiveDecimal(delete.expectedRevision())) {
      return;
    }
    throw new IllegalArgumentException("The operation is invalid.");
  }

  public static boolean equal(TechnicalRecordOperation left, TechnicalRecordOperation right) {
    if (left == null || right == null || !left.kind().equals(right.kind())) {
      return false;
    }
    if (!Objects.equals(left.operationId(), right.operationId())
        || !Objects.equals(left.recordId(), right.recordId())) {
      return false;
    }
    if (left instanceof CreateTechnicalRecordOperation leftCreate
        && right instanceof CreateTechnicalRecordOperation rightCreate) {
      return Objects.equals(leftCreate.value(), rightCreate.value());
    }
    if (left instanceof ReplaceTechnicalRecordOperation leftReplace
        && right instanceof ReplaceTechnicalRecordOperation rightReplace) {
      return Objects.equals(leftReplace.value(), rightReplace.value())
          && Objects.equals(leftReplace.expectedRevision(), rightReplace.expectedRevision());
    }
    return left instanceof DeleteTechnicalRecordOperation leftDelete
        && right instanceof DeleteTechnicalRecordOperation rightDelete
        && Objects.equals(leftDelete.expectedRevision(), rightDelete.expectedRevision());
  }
}
