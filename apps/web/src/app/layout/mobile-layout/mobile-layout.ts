import { Component, inject } from '@angular/core';
import { Device } from '../../utils/device';
import { Routing } from '../../utils/routing';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { RouterOutlet, RouterLinkWithHref, RouterLinkActive } from '@angular/router';
import { MatButton, MatFabButton } from '@angular/material/button';

@Component({
  imports: [MatToolbar, MatIcon, RouterOutlet, MatButton, MatFabButton, RouterLinkWithHref, RouterLinkActive],
  selector: 'hortinis-mobile-layout',
  styleUrl: './mobile-layout.scss',
  templateUrl: './mobile-layout.html',
  host: { '[class.landscape]': 'device.mobileLandscape()' },
})
export class MobileLayout {
  protected readonly device = inject(Device);
  protected readonly routing = inject(Routing);
}
