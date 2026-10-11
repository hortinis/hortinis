import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { liveQuery } from 'dexie';
import {
  LocalBindingExchangePersistence,
  type LocalBindingSnapshot,
} from '../persistence/local-binding-exchange-persistence';
import { sameBinding } from '../persistence/local-exchange-metadata';
import {
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from '../persistence/synchronization-ownership';
import { SynchronizationCoordinator } from './synchronization-coordinator';
import { BINDING_TRANSPORT } from './binding-transport.token';
import { NETWORK_STATUS } from './network-status';
import { SYNCHRONIZATION_CLOCK, SYNCHRONIZATION_SCHEDULER } from './synchronization-runtime';
import {
  parseAccessContract,
  type LocalExchangeStatus,
  type LocalServerBinding,
  type SynchronizationBootstrap,
} from './access-contracts.rule';
import { SynchronizationAccessError } from './binding-transport';

@Injectable({ providedIn: 'root' })
export class SynchronizationBindingService {
  private readonly persistence = inject(LocalBindingExchangePersistence);
  private readonly coordinator = inject(SynchronizationCoordinator);
  private readonly transport = inject(BINDING_TRANSPORT);
  private readonly network = inject(NETWORK_STATUS);
  private readonly clock = inject(SYNCHRONIZATION_CLOCK);
  private readonly scheduler = inject(SYNCHRONIZATION_SCHEDULER);
  private readonly current = signal<LocalExchangeStatus>('offline');
  private readonly busy = signal(false);
  private readonly failed = signal(false);
  private cancelExpiry: (() => void) | undefined;
  private destroyed = false;
  readonly state = this.current.asReadonly();
  readonly connecting = this.busy.asReadonly();
  readonly failedConnection = this.failed.asReadonly();

  constructor() {
    const subscription = liveQuery(() => this.persistence.snapshot()).subscribe({
      next: (snapshot) => this.observe(snapshot),
      error: () => this.failed.set(true),
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      subscription.unsubscribe();
      this.cancelExpiry?.();
    });
  }

  async connect(): Promise<LocalExchangeStatus> {
    if (this.destroyed || this.busy()) return this.current();
    if (!this.network.isOnline()) return 'offline';
    this.busy.set(true);
    this.failed.set(false);
    try {
      const attempt = await this.coordinator.tryAcquireForBinding();
      if (!attempt.acquired) return this.current();
      let lost = false;
      const ownership: SynchronizationOwnership = {
        lease: attempt.lease,
        now: () => this.clock.now(),
        isLost: () => lost || this.destroyed,
      };
      const stop = this.coordinator.keepAlive(attempt.lease, () => {
        lost = true;
      });
      try {
        return await this.bind(ownership);
      } finally {
        stop();
        await this.coordinator.release(attempt.lease);
      }
    } catch {
      this.failed.set(true);
      return this.current();
    } finally {
      this.busy.set(false);
    }
  }

  private async bind(ownership: SynchronizationOwnership): Promise<LocalExchangeStatus> {
    const before = await this.persistence.snapshot();
    if (before.exchange.logoutPending) return 'auth_required';
    if (before.exchange.state === 'bound' && before.initial?.phase !== 'uncertain') return 'bound';
    const started = this.clock.now();
    const observation = await this.observeDestination(before, ownership);
    if (!observation) return (await this.persistence.snapshot()).exchange.state;
    await this.coordinator.assertOwner(ownership);
    const destination = this.destination(before, observation);
    if (before.binding && !sameBinding(before.binding, destination))
      return this.pause(before, 'binding_mismatch', ownership);
    if (before.binding && !before.initial) {
      await this.persistence.resume(destination, ownership);
      return 'bound';
    }
    if (observation.population !== 'empty')
      return this.pause(before, 'reconciliation_required', ownership);
    if (
      !before.binding &&
      (await this.persistence.classifyUnbound()) === 'existing-server-evidence'
    ) {
      return this.pause(before, 'reconciliation_required', ownership);
    }
    const confirmed = await this.confirmDestination(before, observation, destination, ownership);
    if (!confirmed) return (await this.persistence.snapshot()).exchange.state;
    await this.coordinator.assertOwner(ownership);
    await this.persistence.confirm(
      destination,
      confirmed.expectation,
      started + 10 * 60_000,
      ownership,
    );
    return 'bound';
  }

  private async confirmDestination(
    before: LocalBindingSnapshot,
    observation: SynchronizationBootstrap,
    destination: LocalServerBinding,
    ownership: SynchronizationOwnership,
  ): Promise<SynchronizationBootstrap | undefined> {
    await this.persistence.begin(ownership);
    let confirmed: SynchronizationBootstrap;
    try {
      confirmed = await this.transport.confirm({
        expectation: observation.expectation,
        expectedPopulation: 'empty',
      });
    } catch (error) {
      if (error instanceof SynchronizationAccessError) {
        await this.pause(
          before,
          error.body.code === 'AUTHENTICATION_REQUIRED'
            ? 'auth_required'
            : error.body.code === 'BINDING_PRECONDITION_FAILED'
              ? 'reconciliation_required'
              : 'disconnected',
          ownership,
        );
        return undefined;
      }
      throw error;
    }
    if (
      confirmed.population !== 'empty' ||
      confirmed.expectation !== observation.expectation ||
      !sameBinding(destination, this.destination(before, confirmed))
    ) {
      await this.pause(before, 'reconciliation_required', ownership);
      return undefined;
    }
    return confirmed;
  }

  private async observeDestination(
    before: LocalBindingSnapshot,
    ownership: SynchronizationOwnership,
  ): Promise<SynchronizationBootstrap | undefined> {
    const capabilities = await this.transport.capabilities();
    await this.coordinator.assertOwner(ownership);
    if (
      before.binding &&
      (before.binding.serverOrigin !== this.transport.serverOrigin ||
        before.binding.serverInstanceId !== capabilities.serverInstanceId ||
        before.binding.identity.accessMode !== capabilities.accessMode)
    ) {
      await this.pause(before, 'binding_mismatch', ownership);
      return undefined;
    }
    if (!capabilities.supportedBoundaries.includes('generationless')) {
      await this.pause(before, 'reconciliation_required', ownership);
      return undefined;
    }
    let observation: SynchronizationBootstrap;
    try {
      observation = await this.transport.bootstrap();
    } catch (error) {
      if (
        error instanceof SynchronizationAccessError &&
        error.body.code === 'AUTHENTICATION_REQUIRED'
      ) {
        await this.pause(before, 'auth_required', ownership);
        return undefined;
      }
      throw error;
    }
    if (
      observation.serverInstanceId !== capabilities.serverInstanceId ||
      observation.identity.accessMode !== capabilities.accessMode ||
      observation.boundary.kind !== 'generationless'
    ) {
      await this.pause(before, 'binding_mismatch', ownership);
      return undefined;
    }
    return observation;
  }

  private destination(
    before: LocalBindingSnapshot,
    observation: SynchronizationBootstrap,
  ): LocalServerBinding {
    return parseAccessContract('LocalServerBinding', {
      version: '1',
      localDataSetId: before.localDataSetId,
      serverOrigin: this.transport.serverOrigin,
      serverInstanceId: observation.serverInstanceId,
      identity: observation.identity,
      boundary: observation.boundary,
    });
  }

  private async pause(
    before: LocalBindingSnapshot,
    state: 'binding_mismatch' | 'reconciliation_required' | 'auth_required' | 'disconnected',
    ownership: SynchronizationOwnership,
  ): Promise<LocalExchangeStatus> {
    await this.persistence.pause(state, before.exchange.exchangeFence, undefined, ownership);
    return state;
  }

  private observe(snapshot: LocalBindingSnapshot): void {
    this.current.set(snapshot.exchange.state);
    this.cancelExpiry?.();
    this.cancelExpiry = undefined;
    if (snapshot.initial?.phase !== 'confirmed' || snapshot.initial.expiresAt === null) return;
    const remaining = snapshot.initial.expiresAt - this.clock.now();
    const expire = () => {
      if (this.destroyed) return;
      void this.persistence
        .pause('reconciliation_required', snapshot.exchange.exchangeFence, {
          expectation: snapshot.initial?.expectation ?? '',
          now: this.clock.now(),
        })
        .catch((error: unknown) => {
          if (!(error instanceof SynchronizationOwnershipLostError)) this.failed.set(true);
        });
    };
    if (remaining <= 0) expire();
    else this.cancelExpiry = this.scheduler.schedule(expire, remaining);
  }
}
