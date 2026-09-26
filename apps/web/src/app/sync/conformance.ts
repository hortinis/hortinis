export interface TechnicalRecord {
  recordId: string;
  revision: string;
  value: string;
}

export interface TechnicalTombstone {
  recordId: string;
  revision: string;
  deletedAtSequence: string;
}

export interface RecordOperationResult {
  outcome: 'accepted';
  operationId: string;
  record: TechnicalRecord;
  sequence: string;
}

export interface TombstoneOperationResult {
  outcome: 'accepted';
  operationId: string;
  tombstone: TechnicalTombstone;
  sequence: string;
}

export type OperationResult = RecordOperationResult | TombstoneOperationResult;

export interface RecordTechnicalChange {
  operationId: string;
  record: TechnicalRecord;
  sequence: string;
}

export interface TombstoneTechnicalChange {
  operationId: string;
  tombstone: TechnicalTombstone;
  sequence: string;
}

export type TechnicalChange = RecordTechnicalChange | TombstoneTechnicalChange;

export interface ChangePage {
  changes: TechnicalChange[];
  nextCursor: string;
  hasMore: boolean;
}

export function isSyncCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

export interface InvalidRequestError {
  code: 'INVALID_REQUEST';
  message: string;
}

export interface RecordNotFoundError {
  code: 'RECORD_NOT_FOUND';
  message: string;
  operationId: string;
  recordId: string;
}

export interface OperationIdReusedError {
  code: 'OPERATION_ID_REUSED';
  message: string;
  operationId: string;
}

export interface RecordAlreadyExistsError {
  code: 'RECORD_ALREADY_EXISTS';
  message: string;
  operationId: string;
  currentRecord: TechnicalRecord;
}

export interface RecordIdentifierRetiredError {
  code: 'RECORD_IDENTIFIER_RETIRED';
  message: string;
  operationId: string;
  recordId: string;
}

export interface RevisionConflictError {
  code: 'REVISION_CONFLICT';
  message: string;
  operationId: string;
  expectedRevision: string;
  currentRecord: TechnicalRecord;
}

export type ConflictError =
  | OperationIdReusedError
  | RecordAlreadyExistsError
  | RecordIdentifierRetiredError
  | RevisionConflictError;

export type SynchronizationError = InvalidRequestError | RecordNotFoundError | ConflictError;

export interface CreateTechnicalRecordOperation {
  operationId: string;
  recordId: string;
  value: string;
  kind: 'create';
}

export interface ReplaceTechnicalRecordOperation {
  operationId: string;
  recordId: string;
  value: string;
  kind: 'replace';
  expectedRevision: string;
}

export interface DeleteTechnicalRecordOperation {
  operationId: string;
  recordId: string;
  kind: 'delete';
  expectedRevision: string;
}

export type TechnicalRecordOperation =
  CreateTechnicalRecordOperation | ReplaceTechnicalRecordOperation | DeleteTechnicalRecordOperation;

const canonicalUuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const positiveDecimalPattern = /^[1-9][0-9]*$/;

export function isTechnicalRecordOperation(value: unknown): value is TechnicalRecordOperation {
  if (!isRecord(value)) {
    return false;
  }

  const commonKeys = ['kind', 'operationId', 'recordId', 'value'];
  if (
    typeof value['operationId'] !== 'string' ||
    !canonicalUuidPattern.test(value['operationId']) ||
    typeof value['recordId'] !== 'string' ||
    !canonicalUuidPattern.test(value['recordId']) ||
    typeof value['kind'] !== 'string'
  ) {
    return false;
  }

  if (value['kind'] === 'create') {
    return hasExactlyKeys(value, commonKeys) && typeof value['value'] === 'string';
  }

  return (
    (value['kind'] === 'replace' || value['kind'] === 'delete') &&
    hasExactlyKeys(
      value,
      value['kind'] === 'replace'
        ? [...commonKeys, 'expectedRevision']
        : ['kind', 'operationId', 'recordId', 'expectedRevision'],
    ) &&
    (value['kind'] === 'delete' || typeof value['value'] === 'string') &&
    typeof value['expectedRevision'] === 'string' &&
    positiveDecimalPattern.test(value['expectedRevision'])
  );
}

export function isTechnicalTombstone(value: unknown): value is TechnicalTombstone {
  return (
    isRecord(value) &&
    hasExactlyKeys(value, ['recordId', 'revision', 'deletedAtSequence']) &&
    typeof value['recordId'] === 'string' &&
    canonicalUuidPattern.test(value['recordId']) &&
    typeof value['revision'] === 'string' &&
    positiveDecimalPattern.test(value['revision']) &&
    typeof value['deletedAtSequence'] === 'string' &&
    positiveDecimalPattern.test(value['deletedAtSequence'])
  );
}

export function isTechnicalRecord(value: unknown): value is TechnicalRecord {
  return (
    isRecord(value) &&
    hasExactlyKeys(value, ['recordId', 'revision', 'value']) &&
    typeof value['recordId'] === 'string' &&
    canonicalUuidPattern.test(value['recordId']) &&
    typeof value['revision'] === 'string' &&
    positiveDecimalPattern.test(value['revision']) &&
    typeof value['value'] === 'string'
  );
}

export function isOperationResult(value: unknown): value is OperationResult {
  return (
    isRecord(value) &&
    (hasExactlyKeys(value, ['outcome', 'operationId', 'record', 'sequence']) ||
      hasExactlyKeys(value, ['outcome', 'operationId', 'tombstone', 'sequence'])) &&
    value['outcome'] === 'accepted' &&
    typeof value['operationId'] === 'string' &&
    canonicalUuidPattern.test(value['operationId']) &&
    (('record' in value && isTechnicalRecord(value['record'])) ||
      ('tombstone' in value &&
        isTechnicalTombstone(value['tombstone']) &&
        value['tombstone'].deletedAtSequence === value['sequence'])) &&
    typeof value['sequence'] === 'string' &&
    positiveDecimalPattern.test(value['sequence'])
  );
}

export function isTechnicalChange(value: unknown): value is TechnicalChange {
  return (
    isRecord(value) &&
    (hasExactlyKeys(value, ['operationId', 'record', 'sequence']) ||
      hasExactlyKeys(value, ['operationId', 'tombstone', 'sequence'])) &&
    typeof value['operationId'] === 'string' &&
    canonicalUuidPattern.test(value['operationId']) &&
    (('record' in value && isTechnicalRecord(value['record'])) ||
      ('tombstone' in value &&
        isTechnicalTombstone(value['tombstone']) &&
        value['tombstone'].deletedAtSequence === value['sequence'])) &&
    typeof value['sequence'] === 'string' &&
    positiveDecimalPattern.test(value['sequence'])
  );
}

export function isChangePage(value: unknown): value is ChangePage {
  return (
    isRecord(value) &&
    hasExactlyKeys(value, ['changes', 'nextCursor', 'hasMore']) &&
    Array.isArray(value['changes']) &&
    value['changes'].every(isTechnicalChange) &&
    isSyncCursor(value['nextCursor']) &&
    typeof value['hasMore'] === 'boolean'
  );
}

export function isInvalidRequestError(value: unknown): value is InvalidRequestError {
  return (
    isRecord(value) &&
    hasExactlyKeys(value, ['code', 'message']) &&
    value['code'] === 'INVALID_REQUEST' &&
    isNonEmptyString(value['message'])
  );
}

export function isRecordNotFoundError(value: unknown): value is RecordNotFoundError {
  return (
    isRecord(value) &&
    hasExactlyKeys(value, ['code', 'message', 'operationId', 'recordId']) &&
    value['code'] === 'RECORD_NOT_FOUND' &&
    isNonEmptyString(value['message']) &&
    typeof value['operationId'] === 'string' &&
    canonicalUuidPattern.test(value['operationId']) &&
    typeof value['recordId'] === 'string' &&
    canonicalUuidPattern.test(value['recordId'])
  );
}

export function isConflictError(value: unknown): value is ConflictError {
  if (!isRecord(value) || typeof value['code'] !== 'string') return false;

  if (value['code'] === 'OPERATION_ID_REUSED') {
    return (
      hasExactlyKeys(value, ['code', 'message', 'operationId']) &&
      isNonEmptyString(value['message']) &&
      typeof value['operationId'] === 'string' &&
      canonicalUuidPattern.test(value['operationId'])
    );
  }

  if (value['code'] === 'RECORD_ALREADY_EXISTS') {
    return (
      hasExactlyKeys(value, ['code', 'message', 'operationId', 'currentRecord']) &&
      isNonEmptyString(value['message']) &&
      typeof value['operationId'] === 'string' &&
      canonicalUuidPattern.test(value['operationId']) &&
      isTechnicalRecord(value['currentRecord'])
    );
  }

  if (value['code'] === 'RECORD_IDENTIFIER_RETIRED') {
    return (
      hasExactlyKeys(value, ['code', 'message', 'operationId', 'recordId']) &&
      isNonEmptyString(value['message']) &&
      typeof value['operationId'] === 'string' &&
      canonicalUuidPattern.test(value['operationId']) &&
      typeof value['recordId'] === 'string' &&
      canonicalUuidPattern.test(value['recordId'])
    );
  }

  return (
    value['code'] === 'REVISION_CONFLICT' &&
    hasExactlyKeys(value, [
      'code',
      'message',
      'operationId',
      'expectedRevision',
      'currentRecord',
    ]) &&
    isNonEmptyString(value['message']) &&
    typeof value['operationId'] === 'string' &&
    canonicalUuidPattern.test(value['operationId']) &&
    typeof value['expectedRevision'] === 'string' &&
    positiveDecimalPattern.test(value['expectedRevision']) &&
    isTechnicalRecord(value['currentRecord'])
  );
}

export function isSynchronizationError(value: unknown): value is SynchronizationError {
  return isInvalidRequestError(value) || isRecordNotFoundError(value) || isConflictError(value);
}

export function equalTechnicalRecordOperations(left: unknown, right: unknown): boolean {
  if (!isTechnicalRecordOperation(left) || !isTechnicalRecordOperation(right)) {
    return false;
  }
  return (
    left.operationId === right.operationId &&
    left.recordId === right.recordId &&
    (left.kind === 'delete'
      ? true
      : left.value === (right.kind === 'delete' ? undefined : right.value)) &&
    left.kind === right.kind &&
    (left.kind === 'create' ? undefined : left.expectedRevision) ===
      (right.kind === 'create' ? undefined : right.expectedRevision)
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function hasExactlyKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const expected = [...keys].sort();
  const actual = Object.keys(value).sort();
  return expected.length === actual.length && expected.every((key, index) => key === actual[index]);
}
