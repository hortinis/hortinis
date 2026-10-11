import type { LocalDataSetId } from '../sync/access-contracts.rule';

export interface LocalSynchronizationLease {
  readonly exchangeFence: string;
  readonly localDataSetId: LocalDataSetId;
  scope: 'technical-records';
  ownerId: string;
  fencingToken: number;
  expiresAt: number;
}
