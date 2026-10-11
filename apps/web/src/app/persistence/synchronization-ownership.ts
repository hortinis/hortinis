import type { HortinisDatabase } from './hortinis-database';
import type { LocalSynchronizationLease } from './local-synchronization-lease';

export interface SynchronizationOwnership {
  readonly lease: LocalSynchronizationLease;
  readonly now: () => number;
  readonly isLost: () => boolean;
}

export class SynchronizationOwnershipLostError extends Error {
  constructor() {
    super('Synchronization ownership was lost.');
  }
}

// Call within the transaction that also changes synchronization state.
export async function assertSynchronizationOwnership(
  database: HortinisDatabase,
  ownership?: SynchronizationOwnership,
): Promise<void> {
  const localDataSetId = await database.requireLocalDataSetId();
  const exchange = await database.requireLocalExchangeState(localDataSetId);
  if (!ownership) return;
  const current = await database.synchronizationLeases.get(ownership.lease.scope);
  if (
    ownership.isLost() ||
    ownership.lease.localDataSetId !== localDataSetId ||
    ownership.lease.exchangeFence !== exchange.exchangeFence ||
    current?.exchangeFence !== exchange.exchangeFence ||
    current?.localDataSetId !== localDataSetId ||
    current?.ownerId !== ownership.lease.ownerId ||
    current.fencingToken !== ownership.lease.fencingToken ||
    current.expiresAt <= ownership.now()
  ) {
    throw new SynchronizationOwnershipLostError();
  }
}
