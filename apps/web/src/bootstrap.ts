import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

export function start(): Promise<unknown> {
  return bootstrapApplication(App, appConfig);
}
