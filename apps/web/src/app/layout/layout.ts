import { Component, inject } from '@angular/core';
import { Device } from '../utils/device';
import { Routing } from '../utils/routing';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { RouterOutlet, RouterLinkWithHref, RouterLinkActive } from '@angular/router';
import { MatButton } from '@angular/material/button';

@Component({
  imports: [MatToolbar, MatIcon, RouterOutlet, MatButton, RouterLinkWithHref, RouterLinkActive],
  selector: 'hortinis-layout',
  styleUrl: './layout.scss',
  templateUrl: './layout.html',
})
export class Layout {
  protected readonly device = inject(Device);
  protected readonly routing = inject(Routing);
}
