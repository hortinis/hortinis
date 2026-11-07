import { Component, inject, resource } from '@angular/core';
import { Garden as GardenModel } from '../garden';
import { GardenService } from './garden-service';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { CreateGardenHandler } from './create-garden/create-garden-handler';

@Component({
  imports: [MatIconButton, MatIcon],
  selector: 'hortinis-garden',
  styleUrl: './garden.scss',
  templateUrl: './garden.html',
})
export class Garden {
  private readonly createGardenHandler = inject(CreateGardenHandler);
  private readonly gardenPersistence = inject(GardenService);

  protected readonly initGardens = resource({
    loader: async () => await this.gardenPersistence.listEnabledGardens(),
  });

  protected async createGarden() {
    const result = await this.createGardenHandler.createGarden(false);
    if (result) {
      this.initGardens.reload();
    }
  }
  protected async deleteGarden(garden: GardenModel) {
    await this.gardenPersistence.deleteGarden(garden);
    this.initGardens.reload();
  }
}
