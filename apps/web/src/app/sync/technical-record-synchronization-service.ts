import { inject, Injectable, Inject } from '@angular/core';
import type { OperationResult, TechnicalRecordOperation } from './conformance';
import type { SynchronizationTransport } from './synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { NETWORK_STATUS, type NetworkStatus } from './network-status';

export type PushOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'accepted'; operation: TechnicalRecordOperation; result: OperationResult }
  | { status: 'failed'; operation: TechnicalRecordOperation; error: unknown };

@Injectable({ providedIn: 'root' })
export class TechnicalRecordSynchronizationService {
  private readonly persistence: TechnicalRecordPersistence;
  private readonly transport: SynchronizationTransport;
  private readonly network: NetworkStatus;
  private pushInProgress = false;

  constructor(
    // eslint-disable-next-line @angular-eslint/prefer-inject
    persistence?: TechnicalRecordPersistence,
    // eslint-disable-next-line @angular-eslint/prefer-inject
    @Inject(SYNCHRONIZATION_TRANSPORT) transport?: SynchronizationTransport,
    // eslint-disable-next-line @angular-eslint/prefer-inject
    @Inject(NETWORK_STATUS) network?: NetworkStatus,
  ) {
    this.persistence = persistence ?? inject(TechnicalRecordPersistence);
    this.transport = transport ?? inject(SYNCHRONIZATION_TRANSPORT);
    this.network = network ?? inject(NETWORK_STATUS);
  }

  async pushOnePendingOperation(): Promise<PushOutcome> {
    if (this.pushInProgress) {
      return { status: 'empty' };
    }
    if (!this.network.isOnline()) {
      return { status: 'offline' };
    }

    this.pushInProgress = true;
    try {
      const operation = await this.persistence.firstPendingOperation();
      if (!operation) {
        return { status: 'empty' };
      }

      try {
        const result = await this.transport.submitOperation(operation);
        await this.persistence.commitAcceptedResult(operation, result);
        return { status: 'accepted', operation, result };
      } catch (error) {
        return { status: 'failed', operation, error };
      }
    } finally {
      this.pushInProgress = false;
    }
  }
}
