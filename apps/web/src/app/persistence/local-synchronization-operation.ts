import type { GardenOperation } from '../sync/garden-operation';
import type { HortinisUuid } from '../sync/uuid-v7';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';

type SynchronizableEntityType = 'garden' | 'technicalRecords';

interface OutboxEntry<
  TType extends SynchronizableEntityType,
  TOperation,
  TOperationId extends string = HortinisUuid,
  TEntityId extends string = HortinisUuid,
> {
  operationId: TOperationId;
  entityType: TType;
  entityId: TEntityId;
  operation: TOperation;
}

export type PendingGardenOperation = OutboxEntry<'garden', GardenOperation>;
export type PendingTechnicalRecordOperation = OutboxEntry<
  'technicalRecords',
  LocalTechnicalRecordOperation,
  string,
  string
>;

export type LocalSynchronizationOperation =
  PendingTechnicalRecordOperation | PendingGardenOperation;
