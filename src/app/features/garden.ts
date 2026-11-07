import { HortinisUuid } from '../utils/uuid-v7';

export interface Garden {
  id: HortinisUuid;
  name: string;
  enabled: boolean;
}

export interface CreateGardenInput {
  name: string;
}
