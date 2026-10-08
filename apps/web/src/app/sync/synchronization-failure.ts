import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import { SynchronizationRetryBlockedError } from '../persistence/synchronization-retry-persistence';
import type { SynchronizationFailureReason } from './synchronization-outcomes';
import {
  SynchronizationBoundaryError,
  SynchronizationProtocolError,
  SynchronizationUnexpectedResponseError,
  SynchronizationUnavailableError,
} from './synchronization-transport';

export class SynchronizationPersistenceError extends Error {
  constructor(override readonly cause: unknown) {
    super('Synchronization persistence failed.');
  }
}

export function synchronizationFailureReason(error: unknown): SynchronizationFailureReason {
  if (error instanceof SynchronizationUnavailableError) return 'unavailable';
  if (error instanceof SynchronizationProtocolError) return 'protocol';
  if (error instanceof SynchronizationBoundaryError) return 'boundary';
  if (error instanceof SynchronizationUnexpectedResponseError) return 'unexpected-response';
  if (error instanceof SynchronizationPersistenceError) return 'local-persistence';
  return 'unknown';
}

// Tag the origin, rather than treating every Error as a storage failure.
export async function synchronizationPersistence<T>(work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (error) {
    // Ownership and eligibility errors control recovery and must retain their identities.
    if (
      error instanceof SynchronizationOwnershipLostError ||
      error instanceof SynchronizationRetryBlockedError ||
      error instanceof SynchronizationPersistenceError
    )
      throw error;
    throw new SynchronizationPersistenceError(error);
  }
}
