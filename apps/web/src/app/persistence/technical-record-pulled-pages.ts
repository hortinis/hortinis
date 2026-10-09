import type { ChangePage } from '../sync/conformance';
import { isChangePage } from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import type { LocalSynchronizationState } from './local-synchronization-state';
import { TECHNICAL_PULL_RETRY_WORK_ID } from './local-synchronization-retry-state';
import type { SynchronizationOwnership } from './synchronization-ownership';
import type { TechnicalRecordTransactions } from './technical-record-transactions';
import type { TechnicalRecordProjection } from './technical-record-projection';
import { TechnicalRecordPulledTombstones } from './technical-record-pulled-tombstones';

const TECHNICAL_SYNCHRONIZATION_SCOPE = 'technical-records';

export class TechnicalRecordPulledPages {
  private readonly tombstones: TechnicalRecordPulledTombstones;
  constructor(
    private readonly database: HortinisDatabase,
    private readonly projection: TechnicalRecordProjection,
    private readonly transactions: TechnicalRecordTransactions,
  ) {
    this.tombstones = new TechnicalRecordPulledTombstones(database);
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

    await this.transactions.run(
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.deletionConflicts,
        this.database.synchronizationState,
        this.database.acceptedTechnicalRecords,
        this.database.rejectedOperations,
        this.database.synchronizationLeases,
        this.database.synchronizationRetryState,
      ],
      async () => {
        const previousState = await this.database.synchronizationState.get(
          TECHNICAL_SYNCHRONIZATION_SCOPE,
        );
        const repairing = boundary?.repair === true;
        this.assertBoundary(previousState, boundary);
        await this.applyChanges(page, repairing);

        const state: LocalSynchronizationState =
          repairing && page.hasMore
            ? {
                scope: TECHNICAL_SYNCHRONIZATION_SCOPE,
                ...previousState,
                repairCursor: page.nextCursor,
              }
            : { scope: TECHNICAL_SYNCHRONIZATION_SCOPE, cursor: page.nextCursor };
        if (repairing && !page.hasMore) {
          for (const record of await this.database.acceptedTechnicalRecords.toArray()) {
            await this.projection.projectAcceptedRecord(record.recordId);
          }
        }
        await this.database.synchronizationState.put(state);
        await this.database.synchronizationRetryState.delete(TECHNICAL_PULL_RETRY_WORK_ID);
      },
      ownership,
    );
  }
  async pullBoundary(): Promise<{ expectedCursor: string | undefined; repair: boolean }> {
    const state = await this.database.synchronizationState.get(TECHNICAL_SYNCHRONIZATION_SCOPE);
    return {
      expectedCursor: state?.repairRequired ? state.repairCursor : state?.cursor,
      repair: state?.repairRequired === true,
    };
  }
  private assertBoundary(
    state: LocalSynchronizationState | undefined,
    boundary?: { expectedCursor: string | undefined; repair?: boolean },
  ): void {
    if (boundary?.repair && !state?.repairRequired)
      throw new Error('The accepted-state repair is no longer active.');
    if (
      boundary &&
      (boundary.repair ? state?.repairCursor : state?.cursor) !== boundary.expectedCursor
    ) {
      throw new Error('Synchronization ownership changed before the page was committed.');
    }
  }

  private async applyChanges(page: ChangePage, repairing: boolean): Promise<void> {
    for (const change of page.changes) {
      if ('tombstone' in change) {
        await this.tombstones.apply(change);
        continue;
      }

      const record = change.record;
      if (await this.database.technicalTombstones.get(record.recordId)) continue;
      await this.projection.storeAcceptedRecord(record);
      if (!repairing) await this.projection.projectAcceptedRecord(record.recordId);
    }
  }
}
