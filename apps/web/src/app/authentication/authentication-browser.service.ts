import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { AUTHENTICATION_HTTP, AccountRequestError } from './authentication-http.port';
import { AuthenticationCookieGate } from '../sync/authentication-cookie-gate';
import { LocalBindingExchangePersistence } from '../persistence/local-binding-exchange-persistence';

type View = 'closed' | 'anonymous' | 'authenticated' | 'resolution-required' | 'unavailable';
type Status = { state: 'anonymous' } | { state: 'authenticated'; accountId: string };
const LOCK = 'hortinis.authentication-cookie-workflow.v2';

/** Browser coordination only; Spring remains the sole authentication/session authority. */
@Injectable({ providedIn: 'root' })
export class AuthenticationBrowserService implements OnDestroy {
  private readonly http = inject(AUTHENTICATION_HTTP);
  private readonly gate = inject(AuthenticationCookieGate);
  private readonly persistence = inject(LocalBindingExchangePersistence);
  readonly view = signal<View>('closed');
  readonly notice = signal('');
  readonly busy = signal(false);
  private resolved: string | undefined;
  private readonly channel =
    typeof BroadcastChannel === 'undefined' ? undefined : new BroadcastChannel(LOCK);
  private readonly storage = (event: StorageEvent) => {
    if (event.key === AuthenticationCookieGate.marker || event.key === null) this.changed();
  };

  constructor() {
    addEventListener('storage', this.storage);
    if (this.channel)
      this.channel.onmessage = (event) => {
        if (event.data === 'unavailable') this.gate.block();
        this.changed();
      };
  }

  ngOnDestroy(): void {
    removeEventListener('storage', this.storage);
    this.channel?.close();
  }

  async open(): Promise<void> {
    await this.perform(async () => {
      const revision = this.gate.revision();
      await this.lock(async () => {
        this.requireCurrent(revision);
        const status = this.status(await this.http.get<unknown>('/session'));
        this.requireCurrent(revision);
        if (status.state === 'anonymous')
          await this.persistence.pauseAuthentication(() => this.gate.current(revision));
        await this.http.refreshCsrf();
        this.requireCurrent(revision);
        this.resolved = revision;
        this.view.set(status.state);
        this.notice.set(
          this.gate.paused()
            ? 'Synchronization remains paused. Choose sign in or a new sign-out after checking this session.'
            : status.state === 'authenticated'
              ? 'Signed in.'
              : 'Sign in with your email and password.',
        );
      });
    });
  }

  async login(email: string, password: string): Promise<void> {
    await this.mutate(
      async () => {
        const status = this.status(await this.http.login<unknown>(email, password));
        if (status.state !== 'authenticated') throw new AccountRequestError(0);
        const observed = this.status(await this.http.get<unknown>('/session'));
        if (observed.state !== 'authenticated' || observed.accountId !== status.accountId)
          throw new AccountRequestError(401);
      },
      true,
      'Signed in. Reconnect to verify the retained account and server binding.',
    );
  }

  async logout(): Promise<void> {
    if (this.busy()) return;
    await this.perform(async () => {
      // Capture intent before persistence/lock waits: reconnect cannot turn local sign-out into HTTP.
      const mayLogout = navigator.onLine && this.resolved === this.gate.revision();
      let connectionLost = false;
      const offline = () => {
        connectionLost = true;
      };
      addEventListener('offline', offline);
      try {
        const revision = await this.pause();
        if (!mayLogout || connectionLost || !navigator.onLine) {
          this.localSignOutNotice();
          return;
        }
        await this.lock(async () => {
          this.requireCurrent(revision);
          if (connectionLost || !navigator.onLine) {
            this.localSignOutNotice();
            return;
          }
          this.completed(await this.uncertain(revision, () => this.http.postBodyless('/logout')));
          await this.finishAnonymous(
            revision,
            'Signed out. Local data and pending changes remain on this device.',
          );
        });
      } finally {
        removeEventListener('offline', offline);
      }
    });
  }

  private localSignOutNotice(): void {
    this.notice.set(
      'Synchronization is paused locally. The server session may still exist. Reconnect and check session status before choosing sign in or a new sign-out.',
    );
  }

  async requestReset(email: string): Promise<void> {
    await this.perform(async () =>
      this.lock(async () => {
        const value = await this.http.post<{ accepted: true }>(
          '/password-reset/request',
          { email },
          202,
        );
        if (Object.keys(value).join(',') !== 'accepted' || value.accepted !== true)
          throw new AccountRequestError(0);
        this.notice.set(
          'If this address is eligible, reset instructions may be sent. This does not confirm an account or email delivery.',
        );
      }),
    );
  }

  async acceptInvitation(token: string, newPassword: string): Promise<void> {
    await this.mutate(
      () => this.complete('/invitations/accept', { token, newPassword }),
      false,
      'Invitation accepted. Sign in normally with your email and password.',
    );
  }

  async completeReset(token: string, newPassword: string): Promise<void> {
    await this.mutate(
      () => this.complete('/password-reset/complete', { token, newPassword }),
      false,
      'Password reset completed. Sign in normally with your new password.',
    );
  }

  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await this.mutate(
      () => this.complete('/password', { currentPassword, newPassword }),
      false,
      'Password changed. All sessions were revoked. Sign in normally with your new password.',
    );
  }

  private async complete(path: string, body: Record<string, unknown>): Promise<void> {
    const value = await this.http.post<{ completed: true }>(path, body);
    this.completed(value);
  }

  private completed(value: unknown): void {
    if (
      typeof value !== 'object' ||
      value === null ||
      Array.isArray(value) ||
      Object.keys(value).join(',') !== 'completed' ||
      (value as { completed: unknown }).completed !== true
    ) {
      throw new AccountRequestError(0);
    }
  }

  private async mutate(
    operation: () => Promise<void>,
    login: boolean,
    notice: string,
  ): Promise<void> {
    await this.perform(async () => {
      if (login && this.gate.paused() && this.resolved !== this.gate.revision()) {
        throw new Error('Check the current session before signing in.');
      }
      const revision = await this.pause();
      await this.lock(async () => {
        this.requireCurrent(revision);
        await this.uncertain(revision, operation);
        this.requireCurrent(revision);
        if (login) {
          this.gate.markComplete(revision);
          const ready = this.gate.revision();
          await this.persistence.resolveAuthenticationPause(() => this.gate.current(ready));
          this.requireCurrent(ready);
          this.http.clearCsrf();
          await this.http.refreshCsrf();
          this.resolved = this.gate.revision();
          this.view.set('authenticated');
          this.notice.set(notice);
          this.channel?.postMessage('boundary');
        } else await this.finishAnonymous(revision, notice);
      });
    });
  }

  /** A lost response may follow a committed mutation; refresh proof, never repeat the mutation. */
  private async uncertain<T>(revision: string, work: () => Promise<T>): Promise<T> {
    try {
      return await work();
    } catch (error) {
      if (
        this.gate.current(revision) &&
        error instanceof AccountRequestError &&
        (error.status === 0 || error.status === 503)
      ) {
        this.http.clearCsrf();
        try {
          await this.http.refreshCsrf();
        } catch {
          /* Explicit status resolution remains required. */
        }
      }
      throw error;
    }
  }

  private async finishAnonymous(revision: string, notice: string): Promise<void> {
    this.http.clearCsrf();
    await this.http.refreshCsrf();
    this.requireCurrent(revision);
    this.resolved = revision;
    this.view.set('anonymous');
    this.notice.set(notice);
  }

  private async pause(): Promise<string> {
    let revision: string;
    try {
      revision = this.gate.markPending();
    } catch {
      this.http.clearCsrf();
      this.channel?.postMessage('unavailable');
      throw new AccountRequestError(0);
    }
    this.resolved = undefined;
    this.http.clearCsrf();
    this.view.set('resolution-required');
    this.channel?.postMessage('boundary');
    await this.persistence.pauseAuthentication(() => this.gate.current(revision));
    this.requireCurrent(revision);
    return revision;
  }

  private changed(): void {
    this.resolved = undefined;
    this.http.clearCsrf();
    this.view.set('resolution-required');
    this.notice.set(
      'Account state changed in another tab. Check the current session before continuing.',
    );
    try {
      const revision = this.gate.revision();
      if (this.gate.paused())
        void this.persistence
          .pauseAuthentication(() => this.gate.current(revision))
          .catch(() => undefined);
    } catch {
      this.view.set('unavailable');
    }
  }

  private requireCurrent(revision: string): void {
    if (!this.gate.current(revision)) throw new AccountRequestError(0);
  }

  private status(value: unknown): Status {
    if (typeof value !== 'object' || value === null || Array.isArray(value))
      throw new AccountRequestError(0);
    const status = value as Status;
    const keys = Object.keys(value).sort().join(',');
    if (status.state === 'anonymous' && keys === 'state') return status;
    if (
      status.state === 'authenticated' &&
      keys === 'accountId,state' &&
      typeof status.accountId === 'string' &&
      /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/.test(status.accountId)
    )
      return status;
    throw new AccountRequestError(0);
  }

  private async lock(work: () => Promise<void>): Promise<void> {
    if (!navigator.locks) throw new AccountRequestError(0);
    await navigator.locks.request(LOCK, { mode: 'exclusive' }, work);
  }

  private async perform(work: () => Promise<void>): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      await work();
    } catch (error) {
      this.view.set('resolution-required');
      this.notice.set(
        error instanceof AccountRequestError && error.status === 409
          ? 'This link or account state is no longer current. A previous attempt may have completed. Try normal login or request a new reset.'
          : 'The account request could not be completed. Check session status and try a new action. Local data is retained.',
      );
      if (error instanceof AccountRequestError && (error.status === 401 || error.status === 403)) {
        try {
          await this.pause();
          await this.lock(() => this.http.refreshCsrf());
        } catch {
          /* Remain paused. */
        }
      }
      throw new AccountRequestError(error instanceof AccountRequestError ? error.status : 0);
    } finally {
      this.busy.set(false);
    }
  }
}
