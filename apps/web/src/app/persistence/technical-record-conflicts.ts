import type {
  TechnicalRecordOperation,
  RevisionConflictError,
  RecordNotFoundError,
  RecordIdentifierRetiredError,
} from '../sync/conformance';
import { equalTechnicalRecordOperations } from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import { pushRetryWorkId } from './local-synchronization-retry-state';
import { type SynchronizationOwnership } from './synchronization-ownership';
import type { TechnicalRecordTransactions } from './technical-record-transactions';

export class TechnicalRecordConflicts {
  constructor(
    private readonly database: HortinisDatabase,
    private readonly transactions: TechnicalRecordTransactions,
  ) {}
  async commitRevisionConflict(
    operation: TechnicalRecordOperation,
    conflict: RevisionConflictError,
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    if (
      operation.kind === 'create' ||
      conflict.operationId !== operation.operationId ||
      conflict.currentRecord.recordId !== operation.recordId ||
      conflict.expectedRevision !== operation.expectedRevision
    ) {
      throw new Error('The revision conflict does not correspond to the submitted operation.');
    }

    await this.transactions.run(
      [
        this.database.technicalRecords,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.revisionConflicts,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        const pending = await this.database.outboxOperations.get(operation.operationId);
        if (!pending) {
          throw new Error('The conflicted operation is no longer pending.');
        }
        const submitted = toSubmittedTechnicalRecordOperation(pending);
        if (!submitted || !equalTechnicalRecordOperations(submitted, operation)) {
          throw new Error('The persisted operation does not match the submitted operation.');
        }
        if (
          !(await this.database.technicalRecords.get(operation.recordId)) &&
          !(await this.database.pendingDeletionRecords.get(operation.recordId))
        ) {
          throw new Error('The local record for the conflicted operation is missing.');
        }

        await this.database.revisionConflicts.put(conflict);
        await this.database.synchronizationRetryState.delete(
          pushRetryWorkId(operation.operationId),
        );
      },
      ownership,
    );
  }

  async commitDeletionConflict(
    operation: TechnicalRecordOperation,
    error: RecordNotFoundError | RecordIdentifierRetiredError,
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    if (
      error.operationId !== operation.operationId ||
      error.recordId !== operation.recordId ||
      (error.code === 'RECORD_IDENTIFIER_RETIRED' && operation.kind !== 'create') ||
      (error.code === 'RECORD_NOT_FOUND' && operation.kind === 'create')
    ) {
      throw new Error('The missing-record response does not match the submitted operation.');
    }
    await this.transactions.run(
      [
        this.database.outboxOperations,
        this.database.deletionConflicts,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        const pending = await this.database.outboxOperations.get(operation.operationId);
        if (
          !pending ||
          !equalTechnicalRecordOperations(toSubmittedTechnicalRecordOperation(pending), operation)
        ) {
          throw new Error('The persisted operation does not match the submitted operation.');
        }
        await this.database.deletionConflicts.put({
          operationId: operation.operationId,
          recordId: operation.recordId,
          reason: error.code === 'RECORD_NOT_FOUND' ? 'record-not-found' : 'identifier-retired',
        });
        await this.database.synchronizationRetryState.delete(
          pushRetryWorkId(operation.operationId),
        );
      },
      ownership,
    );
  }
}
