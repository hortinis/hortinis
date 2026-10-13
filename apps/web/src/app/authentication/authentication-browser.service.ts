import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { AUTHENTICATION_HTTP } from './authentication-http.port';
import { BrowserWebAuthnAdapter } from './browser-webauthn.adapter';
import { AuthenticationCookieGate } from '../sync/authentication-cookie-gate';

type JsonObject = Record<string, unknown>;
type AuthenticationView =
  'closed' | 'anonymous' | 'authenticated' | 'pending-logout' | 'unavailable';

interface SessionStatus {
  readonly state: string;
  readonly logoutTarget?: string;
}

interface CredentialsResponse {
  readonly credentials: readonly { readonly id: string; readonly label?: string }[];
}

interface SessionsResponse {
  readonly sessions: readonly {
    readonly target: string;
    readonly expiresAt: string;
    readonly current: boolean;
  }[];
}

interface RecoveryStatus {
  readonly remaining: number;
  readonly expiresSoon: boolean;
  readonly expiresAt?: string;
}

interface PendingLogout {
  readonly origin: string;
  readonly target: string;
}

const PENDING_LOGOUT_KEY = 'hortinis.pending-authentication-logout.v1';
const COOKIE_WORKFLOW_LOCK = 'hortinis.authentication-cookie-workflow.v1';

/** Same-origin authentication client; tokens stay in memory and logout intent is non-secret. */
@Injectable({ providedIn: 'root' })
export class AuthenticationBrowserService implements OnDestroy {
  private readonly http = inject(AUTHENTICATION_HTTP);
  private readonly webauthn = inject(BrowserWebAuthnAdapter);
  private readonly cookieGate = inject(AuthenticationCookieGate);
  private readonly origin = globalThis.location.origin;
  readonly view = signal<AuthenticationView>('closed');
  readonly notice = signal('');
  readonly recoveryCodes = signal<readonly string[]>([]);
  readonly credentials = signal<CredentialsResponse['credentials']>([]);
  readonly sessions = signal<SessionsResponse['sessions']>([]);
  readonly recoveryStatus = signal<RecoveryStatus | undefined>(undefined);
  private logoutTarget: string | undefined;
  private channel: BroadcastChannel | undefined;
  private readonly onStorage = (event: StorageEvent) => {
    if (event.key !== PENDING_LOGOUT_KEY) return;
    if (event.newValue !== null) this.applyLogoutPending();
    else this.applyLogoutComplete();
  };

  constructor() {
    globalThis.addEventListener('storage', this.onStorage);
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(COOKIE_WORKFLOW_LOCK);
      this.channel.onmessage = (event: MessageEvent<unknown>) => {
        if (event.data === 'logout-pending') this.applyLogoutPending();
        if (event.data === 'logout-complete') this.applyLogoutComplete();
      };
    }
  }

  ngOnDestroy(): void {
    globalThis.removeEventListener('storage', this.onStorage);
    this.channel?.close();
  }

  /** Reads current state only after an explicit user action, preserving offline-first startup. */
  async open(): Promise<void> {
    if (this.readPendingLogout()) {
      this.view.set('pending-logout');
      await this.completePendingLogout();
      return;
    }
    try {
      await this.refreshStatus();
    } catch {
      this.view.set('unavailable');
      this.notice.set('Account services are unavailable. Your local data remains on this device.');
    }
  }

  /** Retries an online logout after browser storage is available again. */
  async retryPendingLogout(): Promise<void> {
    const pending = this.readPendingLogout();
    if (pending?.target) {
      this.view.set('pending-logout');
      await this.completePendingLogout();
      return;
    }
    if (this.view() === 'pending-logout' && this.logoutTarget) {
      await this.logout();
      return;
    }
    this.notice.set(
      'Sign-out needs attention. Reopen account access to check the current session.',
    );
  }

  /** Performs both required factors and refreshes CSRF after each cookie rotation. */
  async login(login: string, password: string): Promise<void> {
    await this.withCookieLock(async () => {
      if (this.readPendingLogout()) throw new Error('Complete the pending sign-out first.');
      await this.http.ensureCsrf();
      const options = await this.http.post<JsonObject>('/login/password', { login, password });
      await this.http.refreshCsrf();
      const credential = await this.webauthn.assert(options);
      await this.http.post('/login/passkey', credential);
      await this.http.refreshCsrf();
      await this.refreshStatus();
      this.notice.set('Sign-in complete. Synchronization can resume for this account.');
    });
  }

  /** Runs the operator-issued first-owner flow and returns recovery codes for a one-time display. */
  async setup(ticket: string, login: string, password: string): Promise<void> {
    await this.withCookieLock(async () => {
      if (this.readPendingLogout()) throw new Error('Complete the pending sign-out first.');
      await this.http.ensureCsrf();
      const options = await this.http.post<JsonObject>('/setup', { ticket, login, password });
      await this.http.refreshCsrf();
      const credential = await this.webauthn.register(options);
      const result = await this.http.post<{ codes: string[] }>('/setup/passkey', credential);
      this.http.clearCsrf();
      this.logoutTarget = undefined;
      this.recoveryCodes.set(result.codes);
      this.view.set('anonymous');
      this.notice.set('Save these recovery codes now. They will only be shown once.');
    });
  }

  /** Saves a non-authorizing target before the request and keeps it until server logout succeeds. */
  async logout(): Promise<void> {
    await this.withCookieLock(async () => {
      const target = this.logoutTarget;
      if (!target) throw new Error('The active session cannot be confirmed for sign-out.');
      try {
        this.storePendingLogout({ origin: this.origin, target });
      } catch {
        this.cookieGate.markPending();
        this.applyLogoutPending();
        this.broadcast('logout-pending');
        this.notice.set(
          'Sign-out could not be saved. Synchronization is paused in open tabs; keep this page open and retry.',
        );
        throw new Error('Pending sign-out could not be stored.');
      }
      this.cookieGate.markPending();
      this.view.set('pending-logout');
      this.broadcast('logout-pending');
      try {
        await this.sendLogout(target);
      } catch (error) {
        this.notice.set('Sign-out is pending. Synchronization is paused until it completes.');
        throw error;
      }
      this.finishLogout();
    });
  }

  /** Refreshes only minimized current-account management data after explicit sign-in. */
  async loadAccountDetails(): Promise<void> {
    const [credentials, sessions, recovery] = await Promise.all([
      this.http.get<CredentialsResponse>('/credentials'),
      this.http.get<SessionsResponse>('/sessions'),
      this.http.get<RecoveryStatus>('/recovery-codes'),
    ]);
    this.credentials.set(credentials.credentials);
    this.sessions.set(sessions.sessions);
    this.recoveryStatus.set(recovery);
  }

  /** Completes the configured password-plus-passkey step-up without saving either factor. */
  async stepUp(password: string): Promise<void> {
    await this.withCookieLock(async () => {
      if (this.readPendingLogout()) throw new Error('Complete the pending sign-out first.');
      await this.http.ensureCsrf();
      const options = await this.http.post<JsonObject>('/step-up/password', { password });
      await this.http.refreshCsrf();
      await this.http.post('/step-up/passkey', await this.webauthn.assert(options));
      await this.http.refreshCsrf();
      await this.refreshStatus();
    });
  }

  /** Adds a user-verified passkey after a recent two-factor step-up. */
  async addPasskey(): Promise<void> {
    await this.withCookieLock(async () => {
      const options = await this.http.postBodyless<JsonObject>('/credentials/options');
      await this.http.post('/credentials', await this.webauthn.register(options));
      this.http.clearCsrf();
      await this.refreshStatus();
    });
  }

  /** Changes the password only after both normal factors were recently verified. */
  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.post('/password', { currentPassword, newPassword });
      this.http.clearCsrf();
      await this.refreshStatus();
    });
  }

  /** Revokes one server-listed credential; its identifier never grants data authority. */
  async revokeCredential(id: string): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.post('/credentials/revoke', { credentialId: id });
      this.http.clearCsrf();
      await this.refreshStatus();
      this.credentials.set([]);
    });
  }

  /** Revokes one currently listed same-account session by its non-authorizing target. */
  async revokeSession(target: string): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.post('/sessions/revoke', { target });
      await this.loadAccountDetails();
    });
  }

  /** Rotates recovery codes, which are returned only to this view's transient memory. */
  async regenerateRecoveryCodes(): Promise<void> {
    await this.withCookieLock(async () => {
      const result = await this.http.postBodyless<{ codes: string[] }>('/recovery-codes');
      this.http.clearCsrf();
      this.recoveryCodes.set(result.codes);
      this.recoveryStatus.set(undefined);
      await this.refreshStatus();
    });
  }

  /** Starts account-independent forgotten-password recovery using the other enrolled factor. */
  async resetForgottenPassword(
    login: string,
    recoveryCode: string,
    newPassword: string,
  ): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.ensureCsrf();
      const options = await this.http.post<JsonObject>('/recovery/password/options', {
        login,
        recoveryCode,
      });
      await this.http.refreshCsrf();
      await this.http.post('/recovery/password/proof', await this.webauthn.assert(options), true);
      await this.http.post('/recovery/password', { newPassword });
      this.http.clearCsrf();
      this.view.set('anonymous');
      this.notice.set('Password reset is complete. Sign in with the new password and a passkey.');
    });
  }

  /** Consumes the second recovery proof before showing this account's owned credential choices. */
  async beginLostPasskeyRecovery(
    login: string,
    password: string,
    recoveryCode: string,
  ): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.ensureCsrf();
      const choices = await this.http.post<CredentialsResponse>('/recovery/passkey', {
        login,
        password,
        recoveryCode,
      });
      await this.http.refreshCsrf();
      this.credentials.set(choices.credentials);
      this.notice.set('Choose one of the listed credentials to replace.');
    });
  }

  /** Replaces one listed credential within the restricted, already verified recovery session. */
  async completeLostPasskeyRecovery(credentialId: string): Promise<void> {
    if (!this.credentials().some((credential) => credential.id === credentialId)) {
      throw new Error('Choose a credential listed for this recovery.');
    }
    await this.withCookieLock(async () => {
      const options = await this.http.post<JsonObject>(
        '/recovery/passkey/options',
        { credentialId },
        true,
      );
      const credential = await this.webauthn.register(options);
      await this.http.post('/recovery/passkey/proof', credential);
      this.http.clearCsrf();
      this.credentials.set([]);
      this.view.set('anonymous');
      this.notice.set('Passkey replacement is complete. Sign in again with both factors.');
    });
  }

  /** Performs operator-authorized recovery; the new passkey still requires an ordinary login. */
  async operatorRecovery(ticket: string, newPassword: string): Promise<void> {
    await this.withCookieLock(async () => {
      await this.http.ensureCsrf();
      const options = await this.http.post<JsonObject>('/recovery/operator', {
        ticket,
        newPassword,
      });
      await this.http.refreshCsrf();
      await this.http.post('/recovery/operator/proof', await this.webauthn.register(options));
      this.http.clearCsrf();
      this.view.set('anonymous');
      this.notice.set('Recovery is complete. Sign in with the new password and passkey.');
    });
  }

  private async completePendingLogout(): Promise<void> {
    await this.withCookieLock(async () => {
      const pending = this.readPendingLogout();
      if (!pending || pending.origin !== this.origin) {
        this.view.set('unavailable');
        this.notice.set('Sign-out needs attention before another account can sign in.');
        return;
      }
      try {
        await this.sendLogout(pending.target);
        this.finishLogout();
      } catch {
        this.view.set('pending-logout');
        this.notice.set('Sign-out is waiting for a connection. Synchronization remains paused.');
      }
    });
  }

  private async sendLogout(target: string): Promise<void> {
    await this.http.ensureCsrf();
    await this.http.post('/logout', { target });
  }

  private finishLogout(): void {
    localStorage.removeItem(PENDING_LOGOUT_KEY);
    this.cookieGate.markComplete();
    this.applyLogoutComplete();
    this.broadcast('logout-complete');
  }

  private applyLogoutPending(): void {
    this.cookieGate.markPending();
    this.http.clearCsrf();
    this.view.set('pending-logout');
    this.notice.set(
      'Sign-out is pending. Synchronization is paused and local changes are retained.',
    );
  }

  private applyLogoutComplete(): void {
    this.cookieGate.markComplete();
    this.http.clearCsrf();
    this.logoutTarget = undefined;
    this.recoveryCodes.set([]);
    this.view.set('anonymous');
    this.notice.set('Signed out. Local edits and pending changes remain on this device.');
  }

  private async refreshStatus(): Promise<void> {
    const status = await this.http.get<SessionStatus>('/session');
    this.logoutTarget = status.state === 'authenticated' ? status.logoutTarget : undefined;
    this.view.set(status.state === 'authenticated' ? 'authenticated' : 'anonymous');
  }

  private readPendingLogout(): PendingLogout | undefined {
    let value: string | null;
    try {
      value = localStorage.getItem(PENDING_LOGOUT_KEY);
    } catch {
      return undefined;
    }
    if (!value) return undefined;
    try {
      const parsed: unknown = JSON.parse(value);
      if (
        typeof parsed === 'object' &&
        parsed !== null &&
        !Array.isArray(parsed) &&
        Object.keys(parsed).sort().join(',') === 'origin,target' &&
        typeof (parsed as PendingLogout).origin === 'string' &&
        typeof (parsed as PendingLogout).target === 'string'
      ) {
        return parsed as PendingLogout;
      }
    } catch {
      return { origin: this.origin, target: '' };
    }
    return { origin: this.origin, target: '' };
  }

  private storePendingLogout(value: PendingLogout): void {
    localStorage.setItem(PENDING_LOGOUT_KEY, JSON.stringify(value));
  }

  private broadcast(message: 'logout-pending' | 'logout-complete'): void {
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel ??= new BroadcastChannel(COOKIE_WORKFLOW_LOCK);
      this.channel.postMessage(message);
    }
  }

  private async withCookieLock<T>(operation: () => Promise<T>): Promise<T> {
    if (!navigator.locks) {
      throw new Error('This browser cannot safely coordinate account sessions.');
    }
    return navigator.locks.request(COOKIE_WORKFLOW_LOCK, { mode: 'exclusive' }, operation);
  }
}
