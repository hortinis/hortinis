import { inject, Injectable } from '@angular/core';
import type {
  CreateTechnicalRecordOperation,
  ChangePage,
  OperationResult,
  RecordNotFoundError,
  RecordIdentifierRetiredError,
  RevisionConflictError,
  TechnicalRecordOperation,
  TechnicalRecord,
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
import { pushRetryWorkId, TECHNICAL_PULL_RETRY_WORK_ID } from './local-synchronization-retry-state';
import {
  assertSynchronizationOwnership,
  type SynchronizationOwnership,
} from './synchronization-ownership';

import { assertTechnicalRecordValue } from '../sync/technical-record-value';

const TECHNICAL_SYNCHRONIZATION_SCOPE = 'technical-records';

@Injectable({ providedIn: 'root' })
export class TechnicalRecordPersistence {
  private readonly database = inject(HortinisDatabase);

  async commitCreate(operation: CreateTechnicalRecordOperation): Promise<LocalTechnicalRecord> {
    assertTechnicalRecordValue(operation.value);
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
    assertTechnicalRecordValue(value);
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

    await this.database.transaction(
      'rw',
      [
        this.database.technicalRecords,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.revisionConflicts,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
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
    await this.database.transaction(
      'rw',
      this.database.outboxOperations,
      this.database.deletionConflicts,
      this.database.synchronizationLeases,
      this.database.synchronizationRetryState,
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
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
    );
  }

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

    await this.database.transaction(
      'rw',
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.deletionConflicts,
        this.database.acceptedTechnicalRecords,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
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
          await this.database.acceptedTechnicalRecords.delete(operation.recordId);
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

        await this.storeAcceptedRecord(result.record);
        await this.database.outboxOperations.delete(operation.operationId);
        await this.projectAcceptedRecord(operation.recordId);
      },
    );
  }

  async synchronizationCursor(): Promise<string | undefined> {
    const state = await this.database.synchronizationState.get(TECHNICAL_SYNCHRONIZATION_SCOPE);
    return state?.cursor;
  }

  async commitPulledPage(
    page: ChangePage,
    boundary?: { expectedCursor: string | undefined; repair?: boolean },
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
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
        this.database.acceptedTechnicalRecords,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
        const previousState = await this.database.synchronizationState.get(
          TECHNICAL_SYNCHRONIZATION_SCOPE,
        );
        const repairing = boundary?.repair === true;
        if (repairing && !previousState?.repairRequired) {
          throw new Error('The accepted-state repair is no longer active.');
        }
        if (boundary) {
          const current = await this.database.synchronizationState.get(
            TECHNICAL_SYNCHRONIZATION_SCOPE,
          );
          if ((repairing ? current?.repairCursor : current?.cursor) !== boundary.expectedCursor) {
            throw new Error('Synchronization ownership changed before the page was committed.');
          }
        }
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
            await this.database.acceptedTechnicalRecords.delete(tombstone.recordId);
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
                await this.database.synchronizationRetryState.delete(
                  pushRetryWorkId(operation.operationId),
                );
                await this.database.pendingDeletionRecords.delete(tombstone.recordId);
              } else {
                const previousConflict = await this.database.deletionConflicts.get(
                  operation.operationId,
                );
                const preservedLocalRecord =
                  localRecord ?? deletionBase ?? previousConflict?.localRecord;
                await this.database.deletionConflicts.put({
                  operationId: operation.operationId,
                  recordId: tombstone.recordId,
                  reason: 'remote-deletion',
                  tombstone,
                  ...(preservedLocalRecord ? { localRecord: preservedLocalRecord } : {}),
                });
              }
            }
            continue;
          }

          const record = change.record;
          if (await this.database.technicalTombstones.get(record.recordId)) continue;
          await this.storeAcceptedRecord(record);
          if (!repairing) await this.projectAcceptedRecord(record.recordId);
        }

        const state: LocalSynchronizationState =
          repairing && page.hasMore
            ? { ...previousState!, repairCursor: page.nextCursor }
            : { scope: TECHNICAL_SYNCHRONIZATION_SCOPE, cursor: page.nextCursor };
        if (repairing && !page.hasMore) {
          for (const record of await this.database.acceptedTechnicalRecords.toArray()) {
            await this.projectAcceptedRecord(record.recordId);
          }
        }
        await this.database.synchronizationState.put(state);
        await this.database.synchronizationRetryState.delete(TECHNICAL_PULL_RETRY_WORK_ID);
      },
    );
  }
  async pullBoundary(): Promise<{ expectedCursor: string | undefined; repair: boolean }> {
    const state = await this.database.synchronizationState.get(TECHNICAL_SYNCHRONIZATION_SCOPE);
    return {
      expectedCursor: state?.repairRequired ? state.repairCursor : state?.cursor,
      repair: state?.repairRequired === true,
    };
  }

  private async storeAcceptedRecord(record: TechnicalRecord): Promise<void> {
    const current = await this.database.acceptedTechnicalRecords.get(record.recordId);
    if (current && BigInt(current.revision) > BigInt(record.revision)) return;
    if (current?.revision === record.revision && current.value !== record.value) {
      throw new Error('Equal accepted revisions contain different values.');
    }
    await this.database.acceptedTechnicalRecords.put(record);
  }

  private async projectAcceptedRecord(recordId: string): Promise<void> {
    if (await this.database.technicalTombstones.get(recordId)) return;
    const accepted = await this.database.acceptedTechnicalRecords.get(recordId);
    if (!accepted) return;
    const deletion = await this.database.pendingDeletionRecords.get(recordId);
    if (deletion) {
      await this.database.pendingDeletionRecords.put({
        ...deletion,
        lastAcceptedRevision: accepted.revision,
      });
      return;
    }
    const local = await this.database.technicalRecords.get(recordId);
    const pending = await this.database.outboxOperations.where('recordId').equals(recordId).count();
    await this.database.technicalRecords.put({
      recordId,
      value: pending > 0 && local ? local.value : accepted.value,
      lastAcceptedRevision: accepted.revision,
    });
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
