import { HortinisUuid } from '../../sync/uuid-v7';

export interface GardenRecord {
  id: HortinisUuid;
  name: string;
  enabled: boolean;
  lastAcceptedRevision: string | null;
}
