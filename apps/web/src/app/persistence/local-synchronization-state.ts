export interface LocalSynchronizationState {
  scope: string;
  cursor?: string;
  repairRequired?: boolean;
  repairCursor?: string;
}
