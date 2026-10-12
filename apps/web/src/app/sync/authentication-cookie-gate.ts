import { Injectable } from '@angular/core';
import { SynchronizationPausedError } from './synchronization-transport';

/** Origin-wide, non-secret logout fence shared by every tab and local data set. */
@Injectable({ providedIn: 'root' })
export class AuthenticationCookieGate {
  private static readonly marker = 'hortinis.pending-authentication-logout.v1';

  /** Fail closed if a sign-out marker is present or cannot be read. */
  assertExchangeAllowed(): void {
    try {
      if (globalThis.localStorage.getItem(AuthenticationCookieGate.marker) !== null) {
        throw new SynchronizationPausedError();
      }
    } catch (error) {
      if (error instanceof SynchronizationPausedError) throw error;
      throw new SynchronizationPausedError();
    }
  }
}
