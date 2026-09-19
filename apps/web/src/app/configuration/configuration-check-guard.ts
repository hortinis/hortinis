import { inject } from '@angular/core';
import { CanActivateFn, RedirectCommand, Router, UrlTree } from '@angular/router';

export const configurationCheckGuard: CanActivateFn = (route, state) => {
  const router = inject(Router);
  const configurePath = router.parseUrl('/configure');
  //return new RedirectCommand(configurePath);
  return true;
};

export const configuredCheckGuard: CanActivateFn = () => {
  const router = inject(Router);
  const home = router.parseUrl('/home');
  //return new RedirectCommand(home);
  return true;
};
