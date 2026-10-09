import type { OperationResult, TechnicalRecordOperation } from './conformance';
import {
  SynchronizationProtocolError,
  SynchronizationBoundaryError,
  type SynchronizationTransport,
} from './synchronization-transport';
import type { OperationRejectionCategory } from '../persistence/local-rejected-operation';
import type { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { pushRetryWorkId } from '../persistence/local-synchronization-retry-state';
import {
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from '../persistence/synchronization-ownership';
import type { PushOutcome } from './synchronization-outcomes';
import type { SynchronizationExchange } from './synchronization-exchange';
import { operationRejectionCategory } from './technical-record-rejection.rule';
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

    const candidate = await synchronizationPersistence(() =>
      this.persistence.firstPendingOperation(),
    );
    if (!candidate) return { status: 'empty' };
    if ((await synchronizationPersistence(() => this.persistence.pullBoundary())).repair) {
      return {
        status: 'failed',
        operation: candidate,
        error: new Error('Accepted server state must be repaired before upload.'),
        reason: 'boundary',
      };
    }
    const operation = await synchronizationPersistence(() =>
      this.persistence.preparePendingOperation(ownership, this.exchange.timeoutMilliseconds),
    );
    if (!operation) return { status: 'empty' };
    await this.exchange.assertOwner(ownership);

    let result: OperationResult;
    try {
      result = await this.transport.submitOperation(operation);
    } catch (error) {
      return this.handleFailure(operation, error, ownership);
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

  private async handleFailure(
    operation: TechnicalRecordOperation,
    error: unknown,
    ownership: SynchronizationOwnership,
  ): Promise<OwnedPushOutcome> {
    if (error instanceof SynchronizationProtocolError && error.body.code === 'REVISION_CONFLICT') {
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
    const category = operationRejectionCategory(
      error instanceof SynchronizationBoundaryError && error.direction === 'request'
        ? 'request'
        : error instanceof SynchronizationProtocolError
          ? 'protocol'
          : 'other',
      error instanceof SynchronizationProtocolError ? error.body.code : undefined,
    );
    if (category) {
      if (
        error instanceof SynchronizationProtocolError &&
        !this.matchesRejection(operation, error)
      ) {
        return {
          ...(await this.exchange.transportFailure(
            new SynchronizationBoundaryError(
              'The rejection does not match the submitted operation.',
              'response',
            ),
            pushRetryWorkId(operation.operationId),
            ownership,
          )),
          operation,
        };
      }
      return this.reject(operation, category, ownership);
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

  private matchesRejection(
    operation: TechnicalRecordOperation,
    error: SynchronizationProtocolError,
  ): boolean {
    if ('operationId' in error.body && error.body.operationId !== operation.operationId)
      return false;
    if (error.body.code === 'RECORD_ALREADY_EXISTS')
      return (
        operation.kind === 'create' && error.body.currentRecord.recordId === operation.recordId
      );
    return true;
  }

  private async reject(
    operation: TechnicalRecordOperation,
    category: OperationRejectionCategory,
    ownership: SynchronizationOwnership,
  ): Promise<OwnedPushOutcome> {
    try {
      await this.persistence.commitRejectedOperation(operation, category, ownership);
      return { status: 'rejected', operation, category };
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
