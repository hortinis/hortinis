package com.hortinis.sync.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TechnicalOperationValueTypesTest {

  private static final String RECORD_VALUE = "value";
  private static final String OPERATION_ID = "01890f3e-7c5a-7b12-8abc-0123456789ab";
  private static final String RECORD_ID = "01890f3e-7c5a-7b13-8abc-0123456789ab";
  private static final OperationId OPERATION = OperationId.parse(OPERATION_ID);
  private static final RecordId RECORD = RecordId.parse(RECORD_ID);
  private static final ExpectedRevision EXPECTED = ExpectedRevision.parse("1");

  @Test
  void preservesCanonicalIdentityAndRedactsDiagnosticRepresentations() {
    for (String id : new String[] {OPERATION_ID, "01890f3e-7c5a-4b12-8abc-0123456789ab"}) {
      assertThat(OperationId.parse(id).toWire()).isEqualTo(id);
      assertThat(RecordId.parse(id).toWire()).isEqualTo(id);
      assertThat(OperationId.parse(id).toString()).doesNotContain(id);
      assertThat(RecordId.parse(id).toString()).doesNotContain(id);
    }
    assertThat(OPERATION).isNotEqualTo(RecordId.parse(OPERATION_ID));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "not-a-uuid",
        "01890F3E-7C5A-7B12-8ABC-0123456789AB",
        "01890f3e-7c5a-7b12-0abc-0123456789ab",
        "1-1-1-8-1"
      })
  void rejectsNonCanonicalIdentifiers(String value) {
    assertThatThrownBy(() -> OperationId.parse(value)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RecordId.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"0", "-1", "+1", "01", " 1", "1 ", "1.0", "1e2"})
  void rejectsNonCanonicalDecimalSyntax(String value) {
    assertThatThrownBy(() -> Revision.parse(value)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExpectedRevision.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void advancesAcceptedRevisionsExactlyAndPreservesUnboundedExpectations() {
    assertThat(Revision.parse("1").next()).isEqualTo(new Revision(2));
    assertThat(new Revision(Long.MAX_VALUE).toWire()).isEqualTo(Long.toString(Long.MAX_VALUE));
    assertThatThrownBy(() -> new Revision(Long.MAX_VALUE).next())
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> new Revision(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Revision(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Revision.parse("9223372036854775808"))
        .isInstanceOf(IllegalArgumentException.class);
    ExpectedRevision huge = ExpectedRevision.parse("9223372036854775808");
    assertThat(huge.toWire()).isEqualTo("9223372036854775808");
    assertThat(huge.matches(new Revision(Long.MAX_VALUE))).isFalse();
    assertThat(EXPECTED.matches(new Revision(1))).isTrue();
    assertThatThrownBy(() -> new ExpectedRevision(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExpectedRevision(BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExpectedRevision(BigInteger.valueOf(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void operationEqualityIncludesEveryValidatedFieldAndVariant() {
    CreateTechnicalRecordOperation create =
        new CreateTechnicalRecordOperation(OPERATION, RECORD, RECORD_VALUE);
    ReplaceTechnicalRecordOperation replace =
        new ReplaceTechnicalRecordOperation(OPERATION, RECORD, RECORD_VALUE, EXPECTED);
    DeleteTechnicalRecordOperation delete =
        new DeleteTechnicalRecordOperation(OPERATION, RECORD, EXPECTED);
    assertThat(create)
        .isEqualTo(new CreateTechnicalRecordOperation(OPERATION, RECORD, RECORD_VALUE));
    assertThat(replace)
        .isEqualTo(new ReplaceTechnicalRecordOperation(OPERATION, RECORD, RECORD_VALUE, EXPECTED));
    assertThat(delete).isEqualTo(new DeleteTechnicalRecordOperation(OPERATION, RECORD, EXPECTED));
    assertThat(create).isNotEqualTo(replace).isNotEqualTo(delete);
    assertThat(replace).isNotEqualTo(delete);
    assertThat(create)
        .isNotEqualTo(
            new CreateTechnicalRecordOperation(OperationId.parse(RECORD_ID), RECORD, RECORD_VALUE));
    assertThat(create)
        .isNotEqualTo(
            new CreateTechnicalRecordOperation(
                OPERATION, RecordId.parse(OPERATION_ID), RECORD_VALUE));
    assertThat(create)
        .isNotEqualTo(new CreateTechnicalRecordOperation(OPERATION, RECORD, " value"));
    assertThat(replace)
        .isNotEqualTo(new ReplaceTechnicalRecordOperation(OPERATION, RECORD, "other", EXPECTED));
    assertThat(replace)
        .isNotEqualTo(
            new ReplaceTechnicalRecordOperation(
                OPERATION, RECORD, RECORD_VALUE, ExpectedRevision.parse("2")));
    assertThat(delete)
        .isNotEqualTo(
            new DeleteTechnicalRecordOperation(OPERATION, RECORD, ExpectedRevision.parse("2")));
  }

  @Test
  void rejectsMissingTypedOperationFieldsAtConstruction() {
    assertThatThrownBy(() -> new CreateTechnicalRecordOperation(null, RECORD, RECORD_VALUE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CreateTechnicalRecordOperation(OPERATION, null, RECORD_VALUE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new ReplaceTechnicalRecordOperation(OPERATION, RECORD, RECORD_VALUE, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DeleteTechnicalRecordOperation(OPERATION, RECORD, null))
        .isInstanceOf(NullPointerException.class);
  }
}
