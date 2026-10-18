import { Component, HostListener, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterOutlet } from '@angular/router';
import { SynchronizationBindingService } from './sync/synchronization-binding-service';
import { TechnicalRecordSynchronizationService } from './sync/technical-record-synchronization-service';
import { StorageSafetyService } from './storage/storage-safety-service';
import { AuthenticationBrowserService } from './authentication/authentication-browser.service';
import { takeActionFragment } from './authentication/action-fragment';

@Component({
  imports: [FormsModule, RouterOutlet],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  readonly binding = inject(SynchronizationBindingService);
  private readonly synchronization = inject(TechnicalRecordSynchronizationService);
  readonly storageSafety = inject(StorageSafetyService);
  readonly authentication = inject(AuthenticationBrowserService);
  readonly action = signal(takeActionFragment());
  readonly accountPanelOpen = signal(!!this.action());
  email = '';
  password = '';
  newPassword = '';
  currentPassword = '';
  resetEmail = '';

  constructor() {
    if (this.action()) void this.openAccount();
  }

  @HostListener('window:popstate')
  @HostListener('window:pagehide')
  clearSecrets(): void {
    this.password = '';
    this.newPassword = '';
    this.currentPassword = '';
    this.action.set(undefined);
  }

  async connect(): Promise<void> {
    if ((await this.binding.connect()) === 'bound') await this.synchronization.retryNow();
  }

  async openAccount(): Promise<void> {
    this.accountPanelOpen.set(true);
    await this.run(() => this.authentication.open());
  }

  closeAccount(): void {
    this.clearSecrets();
    this.accountPanelOpen.set(false);
  }

  async login(): Promise<void> {
    try {
      await this.authentication.login(this.email, this.password);
    } catch {
      return;
    } finally {
      this.password = '';
    }
    if (this.authentication.view() === 'authenticated') await this.connect();
  }

  async logout(): Promise<void> {
    this.clearSecrets();
    await this.run(() => this.authentication.logout());
  }

  async requestReset(): Promise<void> {
    await this.run(() => this.authentication.requestReset(this.resetEmail));
  }

  async completeAction(): Promise<void> {
    const action = this.action();
    if (!action?.token) return;
    try {
      if (action.kind === 'invitation')
        await this.authentication.acceptInvitation(action.token, this.newPassword);
      else await this.authentication.completeReset(action.token, this.newPassword);
      this.action.set(undefined);
    } catch {
      /* The fixed notice explains uncertain completion and deliberate recovery. */
    } finally {
      this.newPassword = '';
    }
  }

  async changePassword(): Promise<void> {
    try {
      await this.run(() =>
        this.authentication.changePassword(this.currentPassword, this.newPassword),
      );
    } finally {
      this.currentPassword = '';
      this.newPassword = '';
    }
  }

  private async run(work: () => Promise<void>): Promise<void> {
    try {
      await work();
    } catch {
      /* Only the fixed service notice is rendered. */
    }
  }
}
