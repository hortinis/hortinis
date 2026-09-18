import { Routes } from '@angular/router';

export const routes: Routes = [
    {path: '', pathMatch: 'full', redirectTo: 'home'},
    {
        path: 'home',
        loadComponent: async () => import('./features/home/home').then(({Home}) => Home)
    },
    {
        path: 'garden',
        loadComponent: async () => import('./features/garden/garden').then(({Garden}) => Garden)
    },
    {
        path: 'schedule',
        loadComponent: async () => import('./features/schedule/schedule').then(({Schedule}) => Schedule)
    },
    {
        path: 'diary',
        loadComponent: async () => import('./features/diary/diary').then(({Diary}) => Diary)
    }
];
