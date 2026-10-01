import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideBrowserGlobalErrorListeners,
  provideEnvironmentInitializer,
} from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideServiceWorker } from '@angular/service-worker';
import { OverlayContainer } from '@angular/cdk/overlay';
import { TechnicalRecordSynchronizationService } from './sync/technical-record-synchronization-service';
import { AppOverlayContainer } from './overlay/app-overlay-container';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideRouter(routes),
    { provide: OverlayContainer, useClass: AppOverlayContainer },
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
    provideEnvironmentInitializer(() => {
      void inject(TechnicalRecordSynchronizationService).recoverAfterReload();
    }),
  ],
};
