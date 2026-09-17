import type {
  ReplaceTechnicalRecordOperation,
  TechnicalRecordOperation,
} from '../sync/conformance';

export interface DeferredReplaceTechnicalRecordOperation {
  operationId: string;
  recordId: string;
  value: string;
  kind: 'replace';
  expectedRevision: null;
  predecessorOperationId: string;
}

export interface ReadyDependentReplaceTechnicalRecordOperation extends ReplaceTechnicalRecordOperation {
  predecessorOperationId: string;
}

export type LocalTechnicalRecordOperation =
  | TechnicalRecordOperation
  | DeferredReplaceTechnicalRecordOperation
  | ReadyDependentReplaceTechnicalRecordOperation;

export function toSubmittedTechnicalRecordOperation(
  operation: LocalTechnicalRecordOperation,
): TechnicalRecordOperation | undefined {
  if (operation.kind === 'create') {
    return operation;
  }

  if (operation.expectedRevision === null) {
    return undefined;
  }

  return {
    operationId: operation.operationId,
    recordId: operation.recordId,
    value: operation.value,
    kind: 'replace',
    expectedRevision: operation.expectedRevision,
  };
}
