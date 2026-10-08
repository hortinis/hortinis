import type { ChangePage } from './conformance';
import type { SynchronizationTransport } from './synchronization-transport';
import type { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { TECHNICAL_PULL_RETRY_WORK_ID } from '../persistence/local-synchronization-retry-state';
import {
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from '../persistence/synchronization-ownership';
import type { PullOutcome } from './synchronization-outcomes';
import type { SynchronizationExchange } from './synchronization-exchange';
import { synchronizationPersistence } from './synchronization-failure';

export type OwnedPullOutcome = Exclude<
  PullOutcome,
  { status: 'busy' | 'ownership-lost' | 'empty' }
>;
export class TechnicalRecordPull {
  constructor(
    private readonly persistence: TechnicalRecordPersistence,
    private readonly transport: SynchronizationTransport,
    private readonly exchange: SynchronizationExchange,
  ) {}
  async run(ownership: SynchronizationOwnership): Promise<OwnedPullOutcome> {
    if (!this.exchange.network.isOnline()) {
      return { status: 'offline' };
    }

    const boundary = await synchronizationPersistence(() => this.persistence.pullBoundary());
    const cursor = boundary.expectedCursor;
    await this.exchange.reserve(ownership, TECHNICAL_PULL_RETRY_WORK_ID, 'pull');
    let page: ChangePage;
    try {
      page = await this.transport.pullChanges(cursor);
    } catch (error) {
      return this.exchange.transportFailure(error, TECHNICAL_PULL_RETRY_WORK_ID, ownership);
    }

    try {
      await this.persistence.commitPulledPage(page, boundary, ownership);
      return { status: 'applied', page };
    } catch (error) {
      if (error instanceof SynchronizationOwnershipLostError) throw error;
      return {
        status: 'failed',
        error,
        reason: 'local-persistence',
        retryCategory: 'local-persistence',
      };
    }
  }
}
