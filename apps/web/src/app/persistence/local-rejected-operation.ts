import type { LocalTechnicalRecord } from './local-technical-record';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';

export type OperationRejectionCategory =
  'invalid-request' | 'operation-id-reused' | 'record-already-exists';

export interface LocalRejectedOperation {
  operationId: string;
  recordId: string;
  operation: LocalTechnicalRecordOperation;
  category: OperationRejectionCategory;
  localRecord?: LocalTechnicalRecord;
}

export type RejectionSummary =
  { status: 'clear' | 'rejected'; count: number } | { status: 'unavailable'; count: null };
