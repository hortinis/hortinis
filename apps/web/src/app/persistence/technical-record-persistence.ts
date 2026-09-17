import { inject, Injectable } from '@angular/core';
import type {
  CreateTechnicalRecordOperation,
  ChangePage,
  OperationResult,
  RevisionConflictError,
  TechnicalRecordOperation,
} from '../sync/conformance';
import { equalTechnicalRecordOperations, isChangePage } from '../sync/conformance';
import { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecord } from './local-technical-record';
import type {
  DeferredReplaceTechnicalRecordOperation,
  LocalTechnicalRecordOperation,
  ReadyDependentReplaceTechnicalRecordOperation,
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
      this.database.outboxOperations,
      async () => {
        await this.database.technicalRecords.add(localRecord);
        await this.database.outboxOperations.add(operation);
      },
    );

    return localRecord;
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
      operation.kind !== 'replace' ||
      conflict.operationId !== operation.operationId ||
      conflict.currentRecord.recordId !== operation.recordId ||
      conflict.expectedRevision !== operation.expectedRevision
    ) {
      throw new Error('The revision conflict does not correspond to the submitted operation.');
    }

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
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
        if (!(await this.database.technicalRecords.get(operation.recordId))) {
          throw new Error('The local record for the conflicted operation is missing.');
        }

        await this.database.revisionConflicts.put(conflict);
      },
    );
  }

  async commitAcceptedResult(
    operation: TechnicalRecordOperation,
    result: OperationResult,
  ): Promise<void> {
    if (
      result.operationId !== operation.operationId ||
      result.record.recordId !== operation.recordId
    ) {
      throw new Error('The accepted result does not correspond to the submitted operation.');
    }

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.outboxOperations,
      this.database.acceptedOperationResults,
      async () => {
        await this.database.acceptedOperationResults.add(result);
        const localRecord = await this.database.technicalRecords.get(operation.recordId);
        if (!localRecord) {
          throw new Error('The local record for the accepted operation is missing.');
        }

        const successors = await this.database.outboxOperations
          .toCollection()
          .filter(
            (candidate): candidate is DeferredReplaceTechnicalRecordOperation =>
              candidate.kind === 'replace' &&
              candidate.expectedRevision === null &&
              candidate.predecessorOperationId === operation.operationId,
          )
          .toArray();
        if (successors.length > 1) {
          throw new Error('A predecessor cannot have more than one successor.');
        }

        if (successors.length === 1) {
          const successor = successors[0];
          const readySuccessor: ReadyDependentReplaceTechnicalRecordOperation = {
            ...successor,
            expectedRevision: result.record.revision,
          };
          await this.database.outboxOperations.put(readySuccessor);
        }

        await this.database.technicalRecords.put({
          ...localRecord,
          value: successors.length === 0 ? result.record.value : localRecord.value,
          lastAcceptedRevision: result.record.revision,
        });
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
      this.database.technicalRecords,
      this.database.outboxOperations,
      this.database.synchronizationState,
      async () => {
        for (const change of page.changes) {
          const localRecord = await this.database.technicalRecords.get(change.record.recordId);
          const pending = localRecord
            ? await this.database.outboxOperations
                .where('recordId')
                .equals(change.record.recordId)
                .count()
            : 0;
          const currentRevision = localRecord?.lastAcceptedRevision;
          if (currentRevision !== null && currentRevision !== undefined) {
            if (BigInt(change.record.revision) <= BigInt(currentRevision)) {
              continue;
            }
          }

          await this.database.technicalRecords.put({
            recordId: change.record.recordId,
            value: pending > 0 && localRecord ? localRecord.value : change.record.value,
            lastAcceptedRevision: change.record.revision,
          });
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
