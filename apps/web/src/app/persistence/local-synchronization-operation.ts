import { CreateGardenOperation } from '../sync/garden-operation';
import { HortinisUuid } from '../sync/uuid-v7';
import { HortinisTables } from './hortinis-database';
import { LocalTechnicalRecordOperation } from './local-technical-record-operation';

interface OutboxEntry<TType extends HortinisTables, TOperation> {
  operationId: HortinisUuid;
  entityType: TType;
  entityId: HortinisUuid;
  operation: TOperation;
}

export type PendingGardenOperation = OutboxEntry<'garden', CreateGardenOperation>;
export type PendingTechnicalRecordOperation = OutboxEntry<
  'technicalRecords',
  LocalTechnicalRecordOperation
>;

export type LocalSynchronizationOperation =
  PendingTechnicalRecordOperation | PendingGardenOperation;
