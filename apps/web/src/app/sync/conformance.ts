export interface TechnicalRecord {
  recordId: string;
  revision: string;
  value: string;
}

export interface TechnicalRecordOperation {
  operationId: string;
  recordId: string;
  value: string;
  kind: 'create' | 'replace';
  expectedRevision?: string;
}

const uuidV7Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const positiveDecimalPattern = /^[1-9][0-9]*$/;

export function isTechnicalRecordOperation(value: unknown): value is TechnicalRecordOperation {
  if (!isRecord(value)) {
    return false;
  }

  const commonKeys = ['kind', 'operationId', 'recordId', 'value'];
  if (
    typeof value['operationId'] !== 'string' ||
    !uuidV7Pattern.test(value['operationId']) ||
    typeof value['recordId'] !== 'string' ||
    !uuidV7Pattern.test(value['recordId']) ||
    typeof value['value'] !== 'string' ||
    typeof value['kind'] !== 'string'
  ) {
    return false;
  }

  if (value['kind'] === 'create') {
    return hasExactlyKeys(value, commonKeys);
  }

  return (
    value['kind'] === 'replace' &&
    hasExactlyKeys(value, [...commonKeys, 'expectedRevision']) &&
    typeof value['expectedRevision'] === 'string' &&
    positiveDecimalPattern.test(value['expectedRevision'])
  );
}

export function equalTechnicalRecordOperations(left: unknown, right: unknown): boolean {
  if (!isTechnicalRecordOperation(left) || !isTechnicalRecordOperation(right)) {
    return false;
  }
  return (
    left.operationId === right.operationId &&
    left.recordId === right.recordId &&
    left.value === right.value &&
    left.kind === right.kind &&
    left.expectedRevision === right.expectedRevision
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactlyKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const expected = [...keys].sort();
  const actual = Object.keys(value).sort();
  return expected.length === actual.length && expected.every((key, index) => key === actual[index]);
}
