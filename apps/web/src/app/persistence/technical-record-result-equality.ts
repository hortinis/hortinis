import type { OperationResult, TechnicalRecordOperation } from '../sync/conformance';

export function sameAcceptedResult(left: OperationResult, right: OperationResult): boolean {
  if (
    left.operationId !== right.operationId ||
    left.sequence !== right.sequence ||
    left.outcome !== right.outcome
  )
    return false;
  if ('record' in left && 'record' in right) {
    return (
      left.record.recordId === right.record.recordId &&
      left.record.revision === right.record.revision &&
      left.record.value === right.record.value
    );
  }
  if ('tombstone' in left && 'tombstone' in right) {
    return (
      left.tombstone.recordId === right.tombstone.recordId &&
      left.tombstone.revision === right.tombstone.revision &&
      left.tombstone.deletedAtSequence === right.tombstone.deletedAtSequence
    );
  }
  return false;
}

export function sameSubmittedOperation(
  left: TechnicalRecordOperation | undefined,
  right: TechnicalRecordOperation,
): boolean {
  if (
    !left ||
    left.operationId !== right.operationId ||
    left.recordId !== right.recordId ||
    left.kind !== right.kind
  )
    return false;
  if (left.kind === 'create' && right.kind === 'create') return left.value === right.value;
  if (left.kind === 'replace' && right.kind === 'replace')
    return left.value === right.value && left.expectedRevision === right.expectedRevision;
  return (
    left.kind === 'delete' &&
    right.kind === 'delete' &&
    left.expectedRevision === right.expectedRevision
  );
}
