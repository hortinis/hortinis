import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { StorageSafetyService } from './storage/storage-safety-service';

@Component({
  imports: [RouterOutlet],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  readonly storageSafety = inject(StorageSafetyService);
}
