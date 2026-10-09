import type {
  TechnicalRecordOperation,
  OperationResult,
  TombstoneOperationResult,
  RecordOperationResult,
} from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import { pushRetryWorkId } from './local-synchronization-retry-state';
import type { SynchronizationOwnership } from './synchronization-ownership';
import type { TechnicalRecordTransactions } from './technical-record-transactions';
import type { TechnicalRecordProjection } from './technical-record-projection';
import { sameAcceptedResult, sameSubmittedOperation } from './technical-record-result-equality';

export class TechnicalRecordAcceptedResults {
  constructor(
    private readonly database: HortinisDatabase,
    private readonly projection: TechnicalRecordProjection,
    private readonly transactions: TechnicalRecordTransactions,
  ) {}
  async commitAcceptedResult(
    operation: TechnicalRecordOperation,
    result: OperationResult,
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    if (
      result.operationId !== operation.operationId ||
      ('record' in result
        ? operation.kind === 'delete' || result.record.recordId !== operation.recordId
        : operation.kind !== 'delete' ||
          result.tombstone.recordId !== operation.recordId ||
          result.tombstone.deletedAtSequence !== result.sequence ||
          BigInt(result.tombstone.revision) !== BigInt(operation.expectedRevision) + 1n)
    ) {
      throw new Error('The accepted result does not correspond to the submitted operation.');
    }

    await this.transactions.run(
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.deletionConflicts,
        this.database.acceptedTechnicalRecords,
        this.database.synchronizationRetryState,
        this.database.rejectedOperations,
      ],
      async () => {
        const pending = await this.database.outboxOperations.get(operation.operationId);
        await this.database.synchronizationRetryState.delete(
          pushRetryWorkId(operation.operationId),
        );
        if (!pending) {
          const stored = await this.database.acceptedOperationResults.get(operation.operationId);
          if (stored && sameAcceptedResult(stored, result)) return;
        }
        if (
          !pending ||
          !sameSubmittedOperation(toSubmittedTechnicalRecordOperation(pending), operation)
        ) {
          throw new Error('The persisted operation does not match the submitted operation.');
        }
        await this.database.acceptedOperationResults.add(result);
        if ('tombstone' in result) await this.commitTombstone(operation, result);
        else await this.commitLive(operation, result);
      },
      ownership,
    );
  }

  private async commitTombstone(
    operation: TechnicalRecordOperation,
    result: TombstoneOperationResult,
  ): Promise<void> {
    const existingTombstone = await this.database.technicalTombstones.get(operation.recordId);
    if (
      existingTombstone &&
      (existingTombstone.revision !== result.tombstone.revision ||
        existingTombstone.deletedAtSequence !== result.tombstone.deletedAtSequence)
    ) {
      throw new Error('The accepted deletion disagrees with the retained tombstone.');
    }
    if (!(await this.database.pendingDeletionRecords.get(operation.recordId))) {
      throw new Error('The pending deletion record is missing.');
    }
    await this.database.technicalTombstones.put(result.tombstone);
    await this.database.acceptedTechnicalRecords.delete(operation.recordId);
    await this.database.pendingDeletionRecords.delete(operation.recordId);
    await this.database.technicalRecords.delete(operation.recordId);
    await this.database.outboxOperations.delete(operation.operationId);
    return;
  }

  private async commitLive(
    operation: TechnicalRecordOperation,
    result: RecordOperationResult,
  ): Promise<void> {
    const existingTombstone = await this.database.technicalTombstones.get(operation.recordId);
    if (existingTombstone) {
      if (BigInt(result.record.revision) > BigInt(existingTombstone.revision)) {
        throw new Error('A live accepted result follows a retired record identity.');
      }
      await this.database.outboxOperations.delete(operation.operationId);
      await this.database.deletionConflicts.delete(operation.operationId);
      return;
    }

    const localRecord = await this.database.technicalRecords.get(operation.recordId);
    const pendingDeletion = await this.database.pendingDeletionRecords.get(operation.recordId);
    if (!localRecord && !pendingDeletion)
      throw new Error('The local record for the accepted operation is missing.');

    const successors = await this.database.outboxOperations
      .where('predecessorOperationId')
      .equals(operation.operationId)
      .toArray();
    if (successors.length > 1) {
      throw new Error('A predecessor cannot have more than one successor.');
    }

    await this.projection.storeAcceptedRecord(result.record);
    await this.database.outboxOperations.delete(operation.operationId);
    await this.projection.projectAcceptedRecord(operation.recordId);
  }
}
