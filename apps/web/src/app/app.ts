import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SynchronizationBindingService } from './sync/synchronization-binding-service';
import { TechnicalRecordSynchronizationService } from './sync/technical-record-synchronization-service';
import { StorageSafetyService } from './storage/storage-safety-service';

@Component({
  imports: [RouterOutlet],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  readonly binding = inject(SynchronizationBindingService);
  private readonly synchronization = inject(TechnicalRecordSynchronizationService);

  async connect(): Promise<void> {
    if ((await this.binding.connect()) === 'bound') await this.synchronization.retryNow();
  }

  readonly storageSafety = inject(StorageSafetyService);
}
