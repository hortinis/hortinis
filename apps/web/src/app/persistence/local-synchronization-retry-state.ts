export { MAXIMUM_SYNCHRONIZATION_ATTEMPTS } from '../sync/synchronization-retry-policy.rule';

export type SynchronizationRetryPhase = 'push' | 'pull';

export type SynchronizationRetryFailureCategory = 'unavailable' | 'local-persistence';

export interface LocalSynchronizationRetryState {
  workId: string;
  scope: 'technical-records';
  phase: SynchronizationRetryPhase;
  operationId?: string;
  attemptCount: number;
  nextEligibleAt: number | null;
  failureCategory: SynchronizationRetryFailureCategory;
  exhausted: boolean;
  inFlight?: true;
}

export function pushRetryWorkId(operationId: string): string {
  return `technical-records:push:${operationId}`;
}

export const TECHNICAL_PULL_RETRY_WORK_ID = 'technical-records:pull';
