export const MAX_TECHNICAL_RECORD_VALUE_CODE_POINTS = 4_096;

const wellFormedUnicodePattern = /^[^\uD800-\uDFFF]*$/u;

export function isTechnicalRecordValue(value: unknown): value is string {
  if (
    typeof value !== 'string' ||
    value.length > MAX_TECHNICAL_RECORD_VALUE_CODE_POINTS * 2 ||
    value.includes('\u0000') ||
    !wellFormedUnicodePattern.test(value)
  ) {
    return false;
  }
  return Array.from(value).length <= MAX_TECHNICAL_RECORD_VALUE_CODE_POINTS;
}

export class InvalidTechnicalRecordValueError extends Error {
  constructor() {
    super('The value must contain at most 4096 Unicode code points, no NUL, and valid Unicode.');
    this.name = 'InvalidTechnicalRecordValueError';
  }
}

export function assertTechnicalRecordValue(value: unknown): asserts value is string {
  if (!isTechnicalRecordValue(value)) throw new InvalidTechnicalRecordValueError();
}
