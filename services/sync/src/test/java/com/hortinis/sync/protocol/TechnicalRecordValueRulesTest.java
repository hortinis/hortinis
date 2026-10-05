package com.hortinis.sync.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TechnicalRecordValueRulesTest {

  @Test
  void countsCodePointsAndPreservesWhitespaceAndSupplementaryCharacters() {
    for (String value : new String[] {"", " \n\t", "é", "x".repeat(4096), "🌱".repeat(4096)}) {
      assertThat(TechnicalRecordValueRules.isValid(value)).isTrue();
    }
  }

  @Test
  void rejectsOversizedNulAndMalformedUnicodeValues() {
    for (String value :
        new String[] {
          null,
          "x".repeat(4097),
          "🌱".repeat(4097),
          "\u0000",
          String.valueOf(Character.MIN_HIGH_SURROGATE),
          String.valueOf(Character.MIN_LOW_SURROGATE),
          "🌱" + String.valueOf(Character.MIN_HIGH_SURROGATE)
        }) {
      assertThat(TechnicalRecordValueRules.isValid(value)).isFalse();
    }
  }

  @Test
  void protectsDirectServiceOperationsAsWellAsHttpParsing() {
    String operationId = "01890f3e-7c5a-7b12-8abc-0123456789ab";
    String recordId = "01890f3e-7c5a-7b13-8abc-0123456789ab";
    assertThatThrownBy(
            () ->
                OperationRules.validate(
                    new CreateTechnicalRecordOperation(operationId, recordId, "\u0000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OperationRules.validate(
                    new ReplaceTechnicalRecordOperation(
                        operationId, recordId, String.valueOf(Character.MIN_HIGH_SURROGATE), "1")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
