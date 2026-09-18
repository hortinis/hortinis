import { Component, computed, inject, input } from '@angular/core';
import { Routing } from '../../utils/routing';
import { MatIcon } from '@angular/material/icon';
import { MatButton, MatFabButton } from '@angular/material/button';
import { RouterLinkActive, RouterLinkWithHref } from '@angular/router';

@Component({
  imports: [
     MatIcon,  MatButton, MatFabButton, RouterLinkWithHref, RouterLinkActive
  ],
  selector: 'hortinis-layout-navigation',
  styleUrl: './layout-navigation.scss',
  templateUrl: './layout-navigation.html',
  host: {
    '[class.column]': 'column()'
  }
})
export class LayoutNavigation {
  protected readonly routing = inject(Routing);

  public readonly direction = input<'column' | 'row'>('row');
  public readonly buttonDirection = input<'column' | 'row'>('row');

  protected readonly columnButton = computed(() => this.buttonDirection() === 'column')
  protected readonly column = computed(() => this.direction() === 'column')

}
