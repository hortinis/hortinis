import { Routes } from '@angular/router';
import { Layout } from './layout/layout';
import {
  configurationCheckGuard,
  configuredCheckGuard,
} from './configuration/configuration-check-guard';

export const routes: Routes = [
  {
    path: '',
    component: Layout,
    // canActivate: [configurationCheckGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'home',
        loadComponent: async () => import('./features/home/home').then(({ Home }) => Home),
      },
      {
        path: 'garden',
        loadComponent: async () => import('./features/garden/garden').then(({ Garden }) => Garden),
      },
      {
        path: 'schedule',
        loadComponent: async () =>
          import('./features/schedule/schedule').then(({ Schedule }) => Schedule),
      },
      {
        path: 'diary',
        loadComponent: async () => import('./features/diary/diary').then(({ Diary }) => Diary),
      },
    ],
  },
  {
    path: 'configure',
    // canActivate: [configuredCheckGuard],
    loadComponent: async () =>
      import('./configuration/base-configuration').then(
        ({ BaseConfiguration: BaseConfiguration }) => BaseConfiguration,
      ),
  },
];
