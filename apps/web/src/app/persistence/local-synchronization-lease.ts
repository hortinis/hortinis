export interface LocalSynchronizationLease {
  scope: 'technical-records';
  ownerId: string;
  fencingToken: number;
  expiresAt: number;
}
