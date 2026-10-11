import type { HortinisDatabase } from './hortinis-database';
import type { SynchronizationOwnership } from './synchronization-ownership';
import { SynchronizationOwnershipLostError } from './synchronization-ownership';
import { readLocalInitialAdmission } from './local-exchange-metadata';

// Invoke in the same transaction as the known successful acceptance or committed pull.
export async function completeInitialAdmission(
  database: HortinisDatabase,
  ownership?: SynchronizationOwnership,
): Promise<void> {
  const owner = ownership?.lease.localDataSetId ?? (await database.requireLocalDataSetId());
  const state = await database.requireLocalExchangeState(owner);
  const progress = readLocalInitialAdmission(
    await database.localInitialAdmissions.toArray(),
    owner,
    state.exchangeFence,
  );
  if (!progress) return;
  if (
    !ownership ||
    progress.phase !== 'confirmed' ||
    state.state !== 'bound' ||
    progress.expiresAt === null ||
    progress.expiresAt <= ownership.now()
  ) {
    throw new SynchronizationOwnershipLostError();
  }
  await database.localInitialAdmissions.delete(owner);
}
