import { computed, inject, Service, Signal, signal } from '@angular/core';
import { isActive, Router } from '@angular/router';
import { Device } from './device';

export type MainRoute = {
  name: string;
  icon: string;
  badge?: {
    value: string;
  };
} & (
  | {
      route: false;
      click: () => void;
    }
  | { route: true; routerLink: string }
);

@Service()
export class Routing {
  private readonly router = inject(Router);
  private readonly device = inject(Device);

  isActive = isActive('/settings', this.router, {
    paths: 'subset',
    queryParams: 'ignored',
    fragment: 'ignored',
    matrixParams: 'ignored',
  });
  private readonly mainRoutes: Signal<MainRoute[]> = signal([
    {
      icon: 'home',
      name: 'Accueil',
      route: true,
      routerLink: 'home',
    },
    {
      icon: 'local_florist',
      name: 'Jardin',
      route: true,
      routerLink: 'garden',
    },
    {
      icon: 'add',
      name: 'Noter',
      route: false,
      click: () => console.log('record event'),
    },
    {
      icon: 'calendar_month',
      name: 'Planifier',
      route: true,
      routerLink: 'schedule',
    },
    {
      icon: 'history_2',
      name: 'Journal',
      route: true,
      routerLink: 'diary',
    },
  ]);
  public readonly routes = computed(() => {
    return this.mainRoutes()
      .filter((r) => r.route || this.device.mobile())
      .map((r) => ({
        ...r,
        active: r.route ? isActive('/' + r.routerLink, this.router)() : undefined,
      }));
  });
}
