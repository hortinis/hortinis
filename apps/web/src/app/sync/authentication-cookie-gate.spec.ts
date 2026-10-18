import { SynchronizationPausedError } from './synchronization-transport';
import { AuthenticationCookieGate } from './authentication-cookie-gate';

describe('R5 origin-wide account revision', () => {
  beforeEach(() => localStorage.clear());
  it('allows untouched offline/no-auth use', () => {
    expect(() => new AuthenticationCookieGate().assertExchangeAllowed()).not.toThrow();
  });
  it('fences another tab and never makes an old revision current again', () => {
    const first = new AuthenticationCookieGate();
    const second = new AuthenticationCookieGate();
    const original = first.revision();
    const paused = first.markPending();
    expect(() => second.assertExchangeAllowed()).toThrow(SynchronizationPausedError);
    second.markComplete(paused);
    expect(() => second.assertExchangeAllowed()).not.toThrow();
    expect(first.current(original)).toBe(false);
    expect(first.current(paused)).toBe(false);
    expect(() => first.markComplete(paused)).toThrow();
  });
  it('rejects a trailing newline rather than treating a malformed pause as ready', () => {
    localStorage.setItem(AuthenticationCookieGate.marker, `${crypto.randomUUID()}:paused\n`);
    expect(() => new AuthenticationCookieGate().assertExchangeAllowed()).toThrow();
  });

  it('rejects malformed state and unavailable storage', () => {
    localStorage.setItem(AuthenticationCookieGate.marker, '{}');
    expect(() => new AuthenticationCookieGate().assertExchangeAllowed()).toThrow();
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error();
    });
    expect(() => new AuthenticationCookieGate().assertExchangeAllowed()).toThrow();
    spy.mockRestore();
  });
});
