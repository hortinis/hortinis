import { HortinisUuid } from './uuid-v7';

interface BaseGardenOperation {
  operationId: HortinisUuid;
  gardenId: HortinisUuid;
}

export interface CreateGardenOperation extends BaseGardenOperation {
  kind: 'createGarden';
  name: string;
}

export interface DeleteGardenOperation extends BaseGardenOperation {
  kind: 'deleteGarden';
}

export type GardenOperation = CreateGardenOperation | DeleteGardenOperation;
