import { Injectable } from '@angular/core';
import { SynchronizationPausedError } from './synchronization-transport';

/** Origin-wide, non-secret logout fence shared by every tab and local data set. */
@Injectable({ providedIn: 'root' })
export class AuthenticationCookieGate {
  private static readonly marker = 'hortinis.pending-authentication-logout.v1';
  private blockedInMemory = false;

  /** Fail closed in this tab if a durable logout marker could not be written. */
  markPending(): void {
    this.blockedInMemory = true;
  }

  /** Clear the in-memory fence only after the server logout completed. */
  markComplete(): void {
    this.blockedInMemory = false;
  }

  /** Fail closed if a sign-out marker is present or cannot be read. */
  assertExchangeAllowed(): void {
    if (this.blockedInMemory) throw new SynchronizationPausedError();
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
