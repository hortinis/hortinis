import { inject, Injectable } from '@angular/core';
import type {
  CreateTechnicalRecordOperation,
  ChangePage,
  OperationResult,
  RecordNotFoundError,
  RecordIdentifierRetiredError,
  RevisionConflictError,
  TechnicalRecordOperation,
} from '../sync/conformance';
import { equalTechnicalRecordOperations, isChangePage } from '../sync/conformance';
import { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecord } from './local-technical-record';
import type {
  DeferredReplaceTechnicalRecordOperation,
  DeferredDeleteTechnicalRecordOperation,
  LocalTechnicalRecordOperation,
  ReadyDependentReplaceTechnicalRecordOperation,
  ReadyDependentDeleteTechnicalRecordOperation,
} from './local-technical-record-operation';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import type { LocalSynchronizationState } from './local-synchronization-state';

const TECHNICAL_SYNCHRONIZATION_SCOPE = 'technical-records';

@Injectable({ providedIn: 'root' })
export class TechnicalRecordPersistence {
  private readonly database = inject(HortinisDatabase);

  async commitCreate(operation: CreateTechnicalRecordOperation): Promise<LocalTechnicalRecord> {
    const localRecord: LocalTechnicalRecord = {
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: null,
    };

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.technicalTombstones,
      this.database.pendingDeletionRecords,
      this.database.outboxOperations,
      async () => {
        if (
          (await this.database.technicalTombstones.get(operation.recordId)) ||
          (await this.database.pendingDeletionRecords.get(operation.recordId))
        ) {
          throw new Error('The local record identifier has been retired.');
        }
        await this.database.technicalRecords.add(localRecord);
        await this.database.outboxOperations.add(operation);
      },
    );

    return localRecord;
  }

  async commitDelete(
    operationId: string,
    recordId: string,
  ): Promise<LocalTechnicalRecordOperation> {
    let committedOperation: LocalTechnicalRecordOperation;
    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.pendingDeletionRecords,
      this.database.outboxOperations,
      async () => {
        const record = await this.database.technicalRecords.get(recordId);
        if (!record) throw new Error('The local record for deletion is missing.');
        const pending = await this.database.outboxOperations
          .where('recordId')
          .equals(recordId)
          .toArray();
        if (pending.length > 1)
          throw new Error('Only one dependent successor is supported in this technical slice.');
        if (pending.length === 1) {
          const predecessor = pending[0];
          if (predecessor.kind === 'delete') throw new Error('A deletion is already pending.');
          committedOperation = {
            operationId,
            recordId,
            kind: 'delete',
            expectedRevision: null,
            predecessorOperationId: predecessor.operationId,
          } satisfies DeferredDeleteTechnicalRecordOperation;
        } else {
          if (record.lastAcceptedRevision === null)
            throw new Error('The deletion has no accepted predecessor revision.');
          committedOperation = {
            operationId,
            recordId,
            kind: 'delete',
            expectedRevision: record.lastAcceptedRevision,
          };
        }
        await this.database.pendingDeletionRecords.add(record);
        await this.database.technicalRecords.delete(recordId);
        await this.database.outboxOperations.add(committedOperation);
      },
    );
    return committedOperation!;
  }

  async commitReplace(
    operationId: string,
    recordId: string,
    value: string,
  ): Promise<{ record: LocalTechnicalRecord; operation: LocalTechnicalRecordOperation }> {
    let committedOperation: LocalTechnicalRecordOperation;
    let committedRecord: LocalTechnicalRecord;

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.outboxOperations,
      async () => {
        const localRecord = await this.database.technicalRecords.get(recordId);
        if (!localRecord) {
          throw new Error('The local record for the replacement is missing.');
        }

        const pendingForRecord = await this.database.outboxOperations
          .where('recordId')
          .equals(recordId)
          .toArray();
        if (pendingForRecord.length > 1) {
          throw new Error('Only one dependent successor is supported in this technical slice.');
        }

        if (pendingForRecord.length === 1) {
          const predecessor = pendingForRecord[0];
          if (predecessor.kind !== 'create') {
            throw new Error('Only a create predecessor is supported in this technical slice.');
          }
          const deferred: DeferredReplaceTechnicalRecordOperation = {
            operationId,
            recordId,
            value,
            kind: 'replace',
            expectedRevision: null,
            predecessorOperationId: predecessor.operationId,
          };
          committedOperation = deferred;
        } else {
          if (localRecord.lastAcceptedRevision === null) {
            throw new Error('The replacement has no accepted predecessor revision.');
          }
          committedOperation = {
            operationId,
            recordId,
            value,
            kind: 'replace',
            expectedRevision: localRecord.lastAcceptedRevision,
          };
        }

        committedRecord = {
          ...localRecord,
          value,
        };
        await this.database.technicalRecords.put(committedRecord);
        await this.database.outboxOperations.add(committedOperation);
      },
    );

    return { record: committedRecord!, operation: committedOperation! };
  }

  async firstPendingOperation(): Promise<TechnicalRecordOperation | undefined> {
    const pending = await this.database.outboxOperations.orderBy('operationId').toArray();
    for (const operation of pending) {
      if (await this.database.revisionConflicts.get(operation.operationId)) {
        continue;
      }
      if (await this.database.deletionConflicts.get(operation.operationId)) {
        continue;
      }
      const submitted = toSubmittedTechnicalRecordOperation(operation);
      if (!submitted) {
        continue;
      }
      if ('predecessorOperationId' in operation) {
        const predecessorResult = await this.database.acceptedOperationResults.get(
          operation.predecessorOperationId,
        );
        if (!predecessorResult) {
          continue;
        }
      }
      return submitted;
    }
    return undefined;
  }

  async commitRevisionConflict(
    operation: TechnicalRecordOperation,
    conflict: RevisionConflictError,
  ): Promise<void> {
    if (
      operation.kind === 'create' ||
      conflict.operationId !== operation.operationId ||
      conflict.currentRecord.recordId !== operation.recordId ||
      conflict.expectedRevision !== operation.expectedRevision
    ) {
      throw new Error('The revision conflict does not correspond to the submitted operation.');
    }

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.pendingDeletionRecords,
      this.database.outboxOperations,
      this.database.revisionConflicts,
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
      },
    );
  }

  async commitDeletionConflict(
    operation: TechnicalRecordOperation,
    error: RecordNotFoundError | RecordIdentifierRetiredError,
  ): Promise<void> {
    if (
      error.operationId !== operation.operationId ||
      error.recordId !== operation.recordId ||
      (error.code === 'RECORD_IDENTIFIER_RETIRED' && operation.kind !== 'create') ||
      (error.code === 'RECORD_NOT_FOUND' && operation.kind === 'create')
    ) {
      throw new Error('The missing-record response does not match the submitted operation.');
    }
    await this.database.transaction(
      'rw',
      this.database.outboxOperations,
      this.database.deletionConflicts,
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
      },
    );
  }

  async commitAcceptedResult(
    operation: TechnicalRecordOperation,
    result: OperationResult,
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

    await this.database.transaction(
      'rw',
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.deletionConflicts,
      ],
      async () => {
        const pending = await this.database.outboxOperations.get(operation.operationId);
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
        if ('tombstone' in result) {
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
          await this.database.pendingDeletionRecords.delete(operation.recordId);
          await this.database.technicalRecords.delete(operation.recordId);
          await this.database.outboxOperations.delete(operation.operationId);
          return;
        }

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
          .toCollection()
          .filter(
            (
              candidate,
            ): candidate is
              DeferredReplaceTechnicalRecordOperation | DeferredDeleteTechnicalRecordOperation =>
              (candidate.kind === 'replace' || candidate.kind === 'delete') &&
              candidate.expectedRevision === null &&
              candidate.predecessorOperationId === operation.operationId,
          )
          .toArray();
        if (successors.length > 1) {
          throw new Error('A predecessor cannot have more than one successor.');
        }

        if (successors.length === 1) {
          const successor = successors[0];
          const readySuccessor:
            | ReadyDependentReplaceTechnicalRecordOperation
            | ReadyDependentDeleteTechnicalRecordOperation = {
            ...successor,
            expectedRevision: result.record.revision,
          };
          await this.database.outboxOperations.put(readySuccessor);
        }

        if (pendingDeletion) {
          await this.database.pendingDeletionRecords.put({
            ...pendingDeletion,
            lastAcceptedRevision: result.record.revision,
          });
        } else if (localRecord) {
          await this.database.technicalRecords.put({
            ...localRecord,
            value: successors.length === 0 ? result.record.value : localRecord.value,
            lastAcceptedRevision: result.record.revision,
          });
        }
        await this.database.outboxOperations.delete(operation.operationId);
      },
    );
  }

  async synchronizationCursor(): Promise<string | undefined> {
    const state = await this.database.synchronizationState.get(TECHNICAL_SYNCHRONIZATION_SCOPE);
    return state?.cursor;
  }

  async commitPulledPage(page: ChangePage): Promise<void> {
    if (!isChangePage(page)) {
      throw new Error('The pulled change page does not satisfy the synchronization contract.');
    }

    let previousSequence: bigint | undefined;
    const operationIds = new Set<string>();
    for (const change of page.changes) {
      const sequence = BigInt(change.sequence);
      if (previousSequence !== undefined && sequence <= previousSequence) {
        throw new Error('The pulled changes are not in ascending server-sequence order.');
      }
      if (operationIds.has(change.operationId)) {
        throw new Error('The pulled page contains a duplicate operation.');
      }
      previousSequence = sequence;
      operationIds.add(change.operationId);
    }

    await this.database.transaction(
      'rw',
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.deletionConflicts,
        this.database.synchronizationState,
      ],
      async () => {
        for (const change of page.changes) {
          if ('tombstone' in change) {
            const tombstone = change.tombstone;
            const existing = await this.database.technicalTombstones.get(tombstone.recordId);
            if (existing) {
              if (BigInt(existing.revision) > BigInt(tombstone.revision)) continue;
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
            await this.database.technicalRecords.delete(tombstone.recordId);
            for (const operation of pending) {
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
                await this.database.pendingDeletionRecords.delete(tombstone.recordId);
              } else {
                await this.database.deletionConflicts.put({
                  operationId: operation.operationId,
                  recordId: tombstone.recordId,
                  reason: 'remote-deletion',
                  tombstone,
                  ...(localRecord || deletionBase
                    ? { localRecord: localRecord ?? deletionBase }
                    : {}),
                });
              }
            }
            continue;
          }

          const record = change.record;
          if (await this.database.technicalTombstones.get(record.recordId)) continue;
          const localRecord = await this.database.technicalRecords.get(record.recordId);
          const deletionBase = await this.database.pendingDeletionRecords.get(record.recordId);
          const currentRevision =
            localRecord?.lastAcceptedRevision ?? deletionBase?.lastAcceptedRevision;
          if (
            currentRevision !== null &&
            currentRevision !== undefined &&
            BigInt(record.revision) <= BigInt(currentRevision)
          )
            continue;
          const pending = await this.database.outboxOperations
            .where('recordId')
            .equals(record.recordId)
            .count();
          if (deletionBase) {
            await this.database.pendingDeletionRecords.put({
              ...deletionBase,
              lastAcceptedRevision: record.revision,
            });
          } else {
            await this.database.technicalRecords.put({
              recordId: record.recordId,
              value: pending > 0 && localRecord ? localRecord.value : record.value,
              lastAcceptedRevision: record.revision,
            });
          }
        }

        const state: LocalSynchronizationState = {
          scope: TECHNICAL_SYNCHRONIZATION_SCOPE,
          cursor: page.nextCursor,
        };
        await this.database.synchronizationState.put(state);
      },
    );
  }
}

function sameSubmittedOperation(
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
  if (left.kind === 'replace' && right.kind === 'replace') {
    return left.value === right.value && left.expectedRevision === right.expectedRevision;
  }
  return (
    left.kind === 'delete' &&
    right.kind === 'delete' &&
    left.expectedRevision === right.expectedRevision
  );
}

function sameAcceptedResult(left: OperationResult, right: OperationResult): boolean {
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
