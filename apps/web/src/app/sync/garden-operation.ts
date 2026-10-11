import { HortinisUuid } from './uuid-v7';

export interface CreateGardenOperation {
  operationId: HortinisUuid;
  gardenId: HortinisUuid;
  kind: 'createGarden';
  name: string;
}
