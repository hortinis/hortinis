import { inject, Service } from '@angular/core';
import { GardenPersistence } from '../../persistence/features/garden-persistence';
import { CreateGardenInput, Garden } from '../garden';

@Service()
export class GardenService {
  private readonly persistence = inject(GardenPersistence);

  listEnabledGardens(): Promise<Garden[]> {
    return this.persistence.listEnabledGardens();
  }

  createGarden(input: CreateGardenInput): Promise<Garden> {
    return this.persistence.createGarden(input);
  }

  deleteGarden(garden: Garden): Promise<void> {
    return this.persistence.deleteGarden(garden);
  }
}
