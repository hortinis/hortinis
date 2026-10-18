import { Injectable } from '@angular/core';
import { SynchronizationPausedError } from './synchronization-transport';

/** Origin-wide non-secret revision. A pause is never a queued logout request. */
@Injectable({ providedIn: 'root' })
export class AuthenticationCookieGate {
  static readonly marker = 'hortinis.authentication-boundary.v2';
  static readonly completion = 'hortinis.authentication-boundary-completion.v1';
  private failed = false;

  block(): void {
    this.failed = true;
  }

  revision(): string {
    try {
      const value = localStorage.getItem(AuthenticationCookieGate.marker) ?? '';
      if (
        value &&
        !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}:(paused|ready)(?![\s\S])/.test(value)
      )
        throw new Error();
      if (this.failed) throw new Error();
      const completed = localStorage.getItem(AuthenticationCookieGate.completion);
      return value.endsWith(':paused') && completed === value
        ? value.replace(':paused', ':ready')
        : value;
    } catch {
      throw new SynchronizationPausedError();
    }
  }

  current(revision: string): boolean {
    try {
      return this.revision() === revision;
    } catch {
      return false;
    }
  }

  paused(): boolean {
    try {
      return this.revision().endsWith(':paused');
    } catch {
      return true;
    }
  }

  markPending(): string {
    const revision = `${crypto.randomUUID()}:paused`;
    try {
      localStorage.setItem(AuthenticationCookieGate.marker, revision);
      this.failed = false;
    } catch {
      this.failed = true;
      throw new SynchronizationPausedError();
    }
    return revision;
  }

  /** Completion acknowledges one pause; it never overwrites a concurrent tab's newer pause. */
  markComplete(expected: string): string {
    if (!expected.endsWith(':paused') || !this.current(expected))
      throw new SynchronizationPausedError();
    localStorage.setItem(AuthenticationCookieGate.completion, expected);
    const ready = expected.replace(':paused', ':ready');
    if (!this.current(ready)) throw new SynchronizationPausedError();
    return ready;
  }

  assertExchangeAllowed(): void {
    if (this.paused()) throw new SynchronizationPausedError();
  }
}
