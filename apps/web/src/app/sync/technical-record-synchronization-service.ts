import { inject, Injectable } from '@angular/core';
import type { OperationResult, TechnicalRecordOperation } from './conformance';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { NETWORK_STATUS } from './network-status';

export type PushOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'accepted'; operation: TechnicalRecordOperation; result: OperationResult }
  | { status: 'failed'; operation: TechnicalRecordOperation; error: unknown };

@Injectable({ providedIn: 'root' })
export class TechnicalRecordSynchronizationService {
  private readonly persistence = inject(TechnicalRecordPersistence);
  private readonly transport = inject(SYNCHRONIZATION_TRANSPORT);
  private readonly network = inject(NETWORK_STATUS);
  private pushInProgress = false;

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
