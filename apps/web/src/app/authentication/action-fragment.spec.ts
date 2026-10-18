import { captureActionFragment, takeActionFragment } from './action-fragment';

describe('R5 inert action links', () => {
  afterEach(() => {
    history.replaceState(null, '', '/');
    takeActionFragment();
  });
  it.each(['/accept-invitation', '/reset-password'])(
    'clears %s before exposing transient state',
    (path) => {
      history.replaceState(null, '', `${path}#token=${'A'.repeat(43)}`);
      captureActionFragment();
      expect(location.hash).toBe('');
      expect(takeActionFragment()?.token).toBe('A'.repeat(43));
      expect(takeActionFragment()).toBeUndefined();
    },
  );
  it.each(['', '#token=bad', '#token=' + 'A'.repeat(43) + '&token=x'])(
    'does not invent a token from %s',
    (fragment) => {
      history.replaceState(null, '', '/reset-password' + fragment);
      captureActionFragment();
      expect(location.hash).toBe('');
      expect(takeActionFragment()?.token).toBe('');
    },
  );
});
