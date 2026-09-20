import { inject } from '@angular/core';
import { CanActivateFn, RedirectCommand, Router } from '@angular/router';
import { Configuration } from './configuration';

export const configurationCheckGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const configuration = inject(Configuration);
  if (await configuration.configured()) {
    return true;
  }
  const configurePath = router.parseUrl('/configure');
  return new RedirectCommand(configurePath);
};

export const configuredCheckGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const configuration = inject(Configuration);

  if (!(await configuration.configured())) {
    return true;
  }
  const home = router.parseUrl('/home');
  return new RedirectCommand(home);
};
