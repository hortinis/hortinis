import { Component, inject } from '@angular/core';
import { Device } from './utils/device';
import { DesktopLayout } from './layout/desktop-layout/desktop-layout';
import { MobileLayout } from './layout/mobile-layout/mobile-layout';

@Component({
  imports: [DesktopLayout, MobileLayout],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly device = inject(Device);
}
