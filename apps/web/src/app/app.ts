import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterOutlet } from '@angular/router';
import { SynchronizationBindingService } from './sync/synchronization-binding-service';
import { TechnicalRecordSynchronizationService } from './sync/technical-record-synchronization-service';
import { StorageSafetyService } from './storage/storage-safety-service';
import { AuthenticationBrowserService } from './authentication/authentication-browser.service';

@Component({
  imports: [FormsModule, RouterOutlet],
  selector: 'hortinis-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  readonly binding = inject(SynchronizationBindingService);
  private readonly synchronization = inject(TechnicalRecordSynchronizationService);

  async connect(): Promise<void> {
    if ((await this.binding.connect()) === 'bound') await this.synchronization.retryNow();
  }

  readonly storageSafety = inject(StorageSafetyService);
  readonly authentication = inject(AuthenticationBrowserService);
  readonly accountPanelOpen = signal(false);
  loginName = '';
  loginPassword = '';
  setupTicket = '';
  setupLogin = '';
  setupPassword = '';
  recoveryLogin = '';
  recoveryCode = '';
  recoveryNewPassword = '';
  lostKeyLogin = '';
  lostKeyPassword = '';
  lostKeyCode = '';
  lostKeyCredentialId = '';
  operatorTicket = '';
  operatorNewPassword = '';
  currentPassword = '';
  replacementPassword = '';

  async openAccount(): Promise<void> {
    this.accountPanelOpen.set(true);
    await this.authentication.open();
    if (this.authentication.view() === 'authenticated') {
      try {
        await this.authentication.loadAccountDetails();
      } catch {
        this.authentication.notice.set('Account details are temporarily unavailable.');
      }
    }
  }

  async login(): Promise<void> {
    try {
      await this.authentication.login(this.loginName, this.loginPassword);
      this.loginPassword = '';
      const state = await this.binding.connect();
      if (state === 'bound') await this.synchronization.retryNow();
      await this.authentication.loadAccountDetails();
    } catch {
      this.authentication.notice.set('Sign-in failed. Check your details and try again.');
      this.loginPassword = '';
    }
  }

  async setup(): Promise<void> {
    try {
      await this.authentication.setup(this.setupTicket, this.setupLogin, this.setupPassword);
      this.setupTicket = '';
    } catch {
      this.authentication.notice.set(
        'Account setup could not be completed. Check the ticket and try again.',
      );
    } finally {
      this.setupPassword = '';
    }
  }

  async logout(): Promise<void> {
    try {
      await this.authentication.logout();
    } catch {
      // The authentication service reports whether sign-out was durably queued or storage failed.
    }
  }

  async resetForgottenPassword(): Promise<void> {
    try {
      await this.authentication.resetForgottenPassword(
        this.recoveryLogin,
        this.recoveryCode,
        this.recoveryNewPassword,
      );
    } catch {
      this.authentication.notice.set(
        'Password recovery could not be completed. Check the details and try again.',
      );
    } finally {
      this.recoveryCode = '';
      this.recoveryNewPassword = '';
    }
  }

  async replaceLostPasskey(): Promise<void> {
    try {
      await this.authentication.beginLostPasskeyRecovery(
        this.lostKeyLogin,
        this.lostKeyPassword,
        this.lostKeyCode,
      );
    } catch {
      this.authentication.notice.set(
        'Passkey recovery could not be completed. Check the details and try again.',
      );
    } finally {
      this.lostKeyPassword = '';
      this.lostKeyCode = '';
    }
  }

  async completeLostPasskeyRecovery(): Promise<void> {
    try {
      await this.authentication.completeLostPasskeyRecovery(this.lostKeyCredentialId);
      this.lostKeyCredentialId = '';
    } catch {
      this.authentication.notice.set('Passkey replacement could not be completed. Try again.');
    }
  }

  async recoverWithOperatorTicket(): Promise<void> {
    try {
      await this.authentication.operatorRecovery(this.operatorTicket, this.operatorNewPassword);
      this.operatorTicket = '';
    } catch {
      this.authentication.notice.set(
        'Operator recovery could not be completed. Check the ticket and try again.',
      );
    } finally {
      this.operatorNewPassword = '';
    }
  }

  async changePassword(): Promise<void> {
    await this.changeFactor(
      () => this.authentication.changePassword(this.currentPassword, this.replacementPassword),
      'Password changed. Sign in again with the new password.',
      'Password change could not be completed. Verify both factors and try again.',
      true,
    );
  }

  async addPasskey(): Promise<void> {
    await this.changeFactor(
      () => this.authentication.addPasskey(),
      'Passkey added. Sign in again to continue.',
      'Passkey could not be added. Verify both factors and try again.',
    );
  }

  async regenerateRecoveryCodes(): Promise<void> {
    await this.changeFactor(
      () => this.authentication.regenerateRecoveryCodes(),
      'New recovery codes are ready. Save them now.',
      'Recovery codes could not be replaced. Verify both factors and try again.',
    );
  }

  async revokeCredential(credentialId: string): Promise<void> {
    await this.changeFactor(
      () => this.authentication.revokeCredential(credentialId),
      'Passkey revoked. Sign in again to continue.',
      'Passkey could not be revoked. Verify both factors and try again.',
    );
  }

  private async changeFactor(
    operation: () => Promise<void>,
    success: string,
    failure: string,
    clearReplacement = false,
  ): Promise<void> {
    try {
      await this.authentication.stepUp(this.currentPassword);
      await operation();
      this.authentication.notice.set(success);
    } catch {
      this.authentication.notice.set(failure);
    } finally {
      this.currentPassword = '';
      if (clearReplacement) this.replacementPassword = '';
    }
  }

  async revokeSession(target: string): Promise<void> {
    try {
      await this.authentication.revokeSession(target);
    } catch {
      this.authentication.notice.set('That session could not be revoked.');
    }
  }
}
