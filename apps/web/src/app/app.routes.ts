import { Routes } from '@angular/router';
import { Layout } from './layout/layout';

export const routes: Routes = [
  {
    path: '',
    component: Layout,
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
];
