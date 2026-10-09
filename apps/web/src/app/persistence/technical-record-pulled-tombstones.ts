import type { TombstoneTechnicalChange } from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecord } from './local-technical-record';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import { pushRetryWorkId } from './local-synchronization-retry-state';

export class TechnicalRecordPulledTombstones {
  constructor(private readonly database: HortinisDatabase) {}

  async apply(change: TombstoneTechnicalChange): Promise<void> {
    const tombstone = change.tombstone;
    const existing = await this.database.technicalTombstones.get(tombstone.recordId);
    if (existing) {
      if (BigInt(existing.revision) > BigInt(tombstone.revision)) return;
      if (
        BigInt(existing.revision) === BigInt(tombstone.revision) &&
        existing.deletedAtSequence !== tombstone.deletedAtSequence
      ) {
        throw new Error('The pulled tombstone disagrees with the retained deletion.');
      }
    }
    const localRecord = await this.database.technicalRecords.get(tombstone.recordId);
    const deletionBase = await this.database.pendingDeletionRecords.get(tombstone.recordId);
    const pending = await this.database.outboxOperations
      .where('recordId')
      .equals(tombstone.recordId)
      .toArray();
    if (
      localRecord?.lastAcceptedRevision !== null &&
      localRecord?.lastAcceptedRevision !== undefined &&
      BigInt(localRecord.lastAcceptedRevision) > BigInt(tombstone.revision)
    ) {
      throw new Error('The pulled tombstone precedes the accepted local revision.');
    }
    await this.database.technicalTombstones.put(tombstone);
    await this.database.acceptedTechnicalRecords.delete(tombstone.recordId);
    await this.database.technicalRecords.delete(tombstone.recordId);
    for (const operation of pending)
      await this.applyPending(change, operation, localRecord ?? deletionBase);
  }

  private async applyPending(
    change: TombstoneTechnicalChange,
    operation: LocalTechnicalRecordOperation,
    localRecord?: LocalTechnicalRecord,
  ): Promise<void> {
    const tombstone = change.tombstone;
    if (operation.operationId === change.operationId && operation.kind === 'delete') {
      const submitted = toSubmittedTechnicalRecordOperation(operation);
      if (
        !submitted ||
        submitted.kind !== 'delete' ||
        BigInt(submitted.expectedRevision) + 1n !== BigInt(tombstone.revision)
      ) {
        throw new Error('The pulled deletion does not match the pending operation.');
      }
      await this.database.acceptedOperationResults.put({
        outcome: 'accepted',
        operationId: change.operationId,
        tombstone,
        sequence: change.sequence,
      });
      await this.database.outboxOperations.delete(operation.operationId);
      await this.database.synchronizationRetryState.delete(pushRetryWorkId(operation.operationId));
      await this.database.pendingDeletionRecords.delete(tombstone.recordId);
    } else {
      const previousConflict = await this.database.deletionConflicts.get(operation.operationId);
      const preservedLocalRecord = localRecord ?? previousConflict?.localRecord;
      await this.database.deletionConflicts.put({
        operationId: operation.operationId,
        recordId: tombstone.recordId,
        reason: 'remote-deletion',
        tombstone,
        ...(preservedLocalRecord ? { localRecord: preservedLocalRecord } : {}),
      });
    }
  }
}
