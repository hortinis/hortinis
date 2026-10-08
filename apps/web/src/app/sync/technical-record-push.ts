import type { OperationResult } from './conformance';
import {
  SynchronizationProtocolError,
  type SynchronizationTransport,
} from './synchronization-transport';
import type { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { pushRetryWorkId } from '../persistence/local-synchronization-retry-state';
import {
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from '../persistence/synchronization-ownership';
import type { PushOutcome } from './synchronization-outcomes';
import type { SynchronizationExchange } from './synchronization-exchange';
import { synchronizationPersistence } from './synchronization-failure';

export type OwnedPushOutcome = Exclude<PushOutcome, { status: 'busy' | 'ownership-lost' }>;
export class TechnicalRecordPush {
  constructor(
    private readonly persistence: TechnicalRecordPersistence,
    private readonly transport: SynchronizationTransport,
    private readonly exchange: SynchronizationExchange,
  ) {}
  async run(ownership: SynchronizationOwnership): Promise<OwnedPushOutcome> {
    if (!this.exchange.network.isOnline()) {
      return { status: 'offline' };
    }

    const operation = await synchronizationPersistence(() =>
      this.persistence.firstPendingOperation(),
    );
    if (!operation) return { status: 'empty' };
    if ((await synchronizationPersistence(() => this.persistence.pullBoundary())).repair) {
      return {
        status: 'failed',
        operation,
        error: new Error('Accepted server state must be repaired before upload.'),
        reason: 'boundary',
      };
    }
    await this.exchange.reserve(
      ownership,
      pushRetryWorkId(operation.operationId),
      'push',
      operation.operationId,
    );

    let result: OperationResult;
    try {
      result = await this.transport.submitOperation(operation);
    } catch (error) {
      if (
        error instanceof SynchronizationProtocolError &&
        error.body.code === 'REVISION_CONFLICT'
      ) {
        await this.exchange.assertOwner(ownership);
        const conflict = error.body;
        await synchronizationPersistence(() =>
          this.persistence.commitRevisionConflict(operation, conflict, ownership),
        );
        return { status: 'conflict', operation, conflict: error.body };
      }
      if (
        error instanceof SynchronizationProtocolError &&
        (error.body.code === 'RECORD_NOT_FOUND' || error.body.code === 'RECORD_IDENTIFIER_RETIRED')
      ) {
        await this.exchange.assertOwner(ownership);
        const conflict = error.body;
        await synchronizationPersistence(() =>
          this.persistence.commitDeletionConflict(operation, conflict, ownership),
        );
        return { status: 'conflict', operation, conflict: error.body };
      }
      return {
        ...(await this.exchange.transportFailure(
          error,
          pushRetryWorkId(operation.operationId),
          ownership,
        )),
        operation,
      };
    }

    try {
      await this.persistence.commitAcceptedResult(operation, result, ownership);
      return { status: 'accepted', operation, result };
    } catch (error) {
      if (error instanceof SynchronizationOwnershipLostError) throw error;
      return {
        status: 'failed',
        operation,
        error,
        reason: 'local-persistence',
        retryCategory: 'local-persistence',
      };
    }
  }
}
