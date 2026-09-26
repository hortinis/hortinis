import type { TechnicalTombstone } from '../sync/conformance';
import type { LocalTechnicalRecord } from './local-technical-record';

export interface LocalDeletionConflict {
  operationId: string;
  recordId: string;
  reason: 'remote-deletion' | 'record-not-found' | 'identifier-retired';
  tombstone?: TechnicalTombstone;
  localRecord?: LocalTechnicalRecord;
}
