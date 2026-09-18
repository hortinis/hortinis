import { Component, inject } from '@angular/core';
import { MatSidenav, MatSidenavContainer, MatSidenavContent } from '@angular/material/sidenav';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Routing } from '../../utils/routing';
import { MatIcon } from '@angular/material/icon';
import { MatButton } from '@angular/material/button';

@Component({
  imports: [MatSidenav, MatButton, MatSidenavContainer, MatSidenavContent,RouterLinkActive, RouterOutlet, RouterLink, MatIcon],
  selector: 'hortinis-desktop-layout',
  styleUrl: './desktop-layout.scss',
  templateUrl: './desktop-layout.html',
})
export class DesktopLayout {
    protected readonly routing = inject(Routing);

}
