import { SynchronizationPausedError } from './synchronization-transport';
import { AuthenticationCookieGate } from './authentication-cookie-gate';

describe('origin-wide authentication cookie gate', () => {
  const marker = 'hortinis.pending-authentication-logout.v1';
  let gate: AuthenticationCookieGate;

  beforeEach(() => {
    localStorage.clear();
    gate = new AuthenticationCookieGate();
  });

  it('allows synchronization when there is no pending sign-out', () => {
    expect(() => gate.assertExchangeAllowed()).not.toThrow();
  });

  it('pauses every data set while a non-secret sign-out marker exists', () => {
    localStorage.setItem(
      marker,
      JSON.stringify({ origin: location.origin, target: 'non-authorizing-target' }),
    );
    expect(() => gate.assertExchangeAllowed()).toThrow(SynchronizationPausedError);
  });

  it('fails closed when the browser cannot read the shared marker', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('unavailable');
    });
    expect(() => gate.assertExchangeAllowed()).toThrow(SynchronizationPausedError);
    getItem.mockRestore();
  });
});
