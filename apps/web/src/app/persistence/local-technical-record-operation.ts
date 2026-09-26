import type {
  DeleteTechnicalRecordOperation,
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

export interface DeferredDeleteTechnicalRecordOperation {
  operationId: string;
  recordId: string;
  kind: 'delete';
  expectedRevision: null;
  predecessorOperationId: string;
}

export interface ReadyDependentDeleteTechnicalRecordOperation extends DeleteTechnicalRecordOperation {
  predecessorOperationId: string;
}

export type LocalTechnicalRecordOperation =
  | TechnicalRecordOperation
  | DeferredReplaceTechnicalRecordOperation
  | ReadyDependentReplaceTechnicalRecordOperation
  | DeferredDeleteTechnicalRecordOperation
  | ReadyDependentDeleteTechnicalRecordOperation;

export function toSubmittedTechnicalRecordOperation(
  operation: LocalTechnicalRecordOperation,
): TechnicalRecordOperation | undefined {
  if (operation.kind === 'create') {
    return operation;
  }

  if (operation.expectedRevision === null) {
    return undefined;
  }

  if (operation.kind === 'delete') {
    return {
      operationId: operation.operationId,
      recordId: operation.recordId,
      kind: 'delete',
      expectedRevision: operation.expectedRevision,
    };
  }

  return {
    operationId: operation.operationId,
    recordId: operation.recordId,
    value: operation.value,
    kind: 'replace',
    expectedRevision: operation.expectedRevision,
  };
}
