import { captureActionFragment } from './app/authentication/action-fragment';

captureActionFragment();
void import('./bootstrap')
  .then(({ start }) => start())
  .catch(() => {
    document.body.textContent = 'Hortinis could not start. Local data is retained.';
  });
