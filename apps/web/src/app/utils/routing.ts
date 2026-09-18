import { Service, Signal, signal } from '@angular/core';

export type MainRoute = {
  name: string;
  icon: string;
  badge?: {
    value: string;
  };
} & ({
  route: false;
  click: () => void
} | { route: true,
  routerLink: string
})

@Service()
export class Routing {
  public readonly mainRoutes: Signal<MainRoute[]> = signal([
    {
      icon: 'home',
      name: 'Accueil',
      route: true,
      routerLink: 'home'
    },
    {
      icon: 'local_florist',
      name: 'Jardin',
      route: true,
      routerLink: 'garden'
    },
    {
      icon: 'add',
      name: 'Noter',
      route: false,
      click: () => console.log('record event')
    },
    {
      icon: 'calendar_month',
      name: 'Planifier',
      route: true,
      routerLink: 'schedule'
    },
    {
      icon: 'history_2',
      name: 'Journal',
      route: true,
      routerLink: 'diary'
    },
  ]);
}
