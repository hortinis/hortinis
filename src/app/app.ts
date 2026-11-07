import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Device } from './utils/device';

@Component({
  imports: [RouterOutlet],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
  host: {
    '[class.layout]': 'true',
    '[class.landscape]': 'device.landscape()',
    '[class.mobile]': 'device.mobile()',
    '[class.handset]': 'device.handset()',
  },
})
export class App {
  protected readonly device = inject(Device);
}
