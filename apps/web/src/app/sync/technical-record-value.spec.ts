import { describe, expect, it } from 'vitest';
import {
  assertTechnicalRecordValue,
  InvalidTechnicalRecordValueError,
  isTechnicalRecordValue,
} from './technical-record-value';
import { isTechnicalRecordOperation, isTechnicalRecord } from './conformance';

describe('technical record value bounds', () => {
  it.each(['', ' \n\t', 'é', 'e\u0301', '🌱'.repeat(4096), 'x'.repeat(4096)])(
    'accepts unchanged valid values within the code point limit',
    (value) => {
      expect(isTechnicalRecordValue(value)).toBe(true);
      expect(() => assertTechnicalRecordValue(value)).not.toThrow();
    },
  );

  it.each([null, 1, 'x'.repeat(4097), '🌱'.repeat(4097), '\u0000', '\uD800', '\uDC00', '🌱\uD800'])(
    'rejects unstorable and oversized values without exposing them',
    (value) => {
      expect(isTechnicalRecordValue(value)).toBe(false);
      expect(() => assertTechnicalRecordValue(value)).toThrow(InvalidTechnicalRecordValueError);
    },
  );

  it('applies the value rule to operations and returned records', () => {
    const operation = {
      operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
      recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
      kind: 'create',
      value: '🌱'.repeat(4096),
    };
    expect(isTechnicalRecordOperation(operation)).toBe(true);
    expect(isTechnicalRecordOperation({ ...operation, value: '\u0000' })).toBe(false);
    expect(
      isTechnicalRecordOperation({ ...operation, kind: 'replace', expectedRevision: '1' }),
    ).toBe(true);
    expect(
      isTechnicalRecordOperation({
        ...operation,
        kind: 'replace',
        expectedRevision: '1',
        value: '\uD800',
      }),
    ).toBe(false);
    const record = { recordId: operation.recordId, revision: '1', value: operation.value };
    expect(isTechnicalRecord(record)).toBe(true);
    expect(isTechnicalRecord({ ...record, value: 'x'.repeat(4097) })).toBe(false);
  });
});
