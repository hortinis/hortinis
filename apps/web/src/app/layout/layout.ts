import { Component, inject } from '@angular/core';
import { Device } from '../utils/device';
import { Routing } from '../utils/routing';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { RouterOutlet, RouterLinkWithHref, RouterLinkActive } from '@angular/router';
import { MatButton, MatFabButton, MatIconButton } from '@angular/material/button';

@Component({
  imports: [
    MatToolbar,
    MatIcon,
    RouterOutlet,
    MatButton,
    MatFabButton,
    RouterLinkWithHref,
    RouterLinkActive,
    MatIconButton,
  ],
  selector: 'hortinis-layout',
  styleUrl: './layout.scss',
  templateUrl: './layout.html',
  host: {
    '[class.landscape]': 'device.landscape()',
    '[class.mobile]': 'device.mobile()',
  },
})
export class Layout {
  protected readonly device = inject(Device);
  protected readonly routing = inject(Routing);
}
