import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideServiceWorker } from '@angular/service-worker';
import { OverlayContainer } from '@angular/cdk/overlay';
import { AppOverlayContainer } from './overlay/app-overlay-container';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';

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
    provideAppInitializer(() => {
      const registry = inject(MatIconRegistry);
      const sanitizer = inject(DomSanitizer);

      for (const name of ['home', 'garden', 'schedule', 'diary', 'note']) {
        registry.addSvgIconInNamespace(
          'hortinis',
          name,
          sanitizer.bypassSecurityTrustResourceUrl(`icons/navigation/${name}.svg`),
        );
      }
    }),
  ],
};
