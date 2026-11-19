import type { CatalogEntry, CatalogVersion } from '../../features/catalog-preview/catalog-record';

export interface StoredCatalogEntry extends CatalogEntry {
  snapshotId: string;
  id: string;
}

export interface CatalogSnapshot extends CatalogVersion {
  id: string;
  recordCount: number;
}

export interface CatalogSnapshotState {
  id: 'preview';
  active?: CatalogSnapshot;
  previous?: CatalogSnapshot;
  stagingId?: string;
}
