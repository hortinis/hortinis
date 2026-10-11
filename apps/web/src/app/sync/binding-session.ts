import { inject, Injectable } from '@angular/core';
import {
  LocalBindingExchangePersistence,
  type LocalBindingSnapshot,
} from '../persistence/local-binding-exchange-persistence';
import { sameBinding } from '../persistence/local-exchange-metadata';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import { BINDING_TRANSPORT } from './binding-transport.token';
import { SYNCHRONIZATION_CLOCK } from './synchronization-runtime';
import {
  SynchronizationPausedError,
  SynchronizationUnavailableError,
  type ExchangeAttemptBoundary,
} from './synchronization-transport';
import {
  parseAccessContract,
  type LocalServerBinding,
  type SynchronizationBootstrap,
} from './access-contracts.rule';
import type { AccessError } from './access-contracts.rule';
import { SynchronizationAccessError } from './binding-transport';

export interface BindingRequestContext {
  readonly binding: LocalServerBinding;
  readonly exchangeFence: string;
  readonly expectation: string;
}

@Injectable({ providedIn: 'root' })
export class BindingSession {
  private readonly persistence = inject(LocalBindingExchangePersistence);
  private readonly transport = inject(BINDING_TRANSPORT);
  private readonly clock = inject(SYNCHRONIZATION_CLOCK);
  private prepared: ExchangeAttemptBoundary | undefined;
  private cached:
    { fencingToken: number; expiresAt: number; context: BindingRequestContext } | undefined;

  async prepare(phase: 'push' | 'pull', boundary: ExchangeAttemptBoundary): Promise<void> {
    const snapshot = await this.persistence.snapshot();
    const binding = await this.requireReady(snapshot);
    if (
      snapshot.localDataSetId !== boundary.localDataSetId ||
      snapshot.exchange.exchangeFence !== boundary.exchangeFence
    ) {
      throw new SynchronizationOwnershipLostError();
    }
    if (binding.serverOrigin !== this.transport.serverOrigin) {
      await this.pause(snapshot, 'binding_mismatch');
    }
    if (phase === 'pull' && snapshot.initial && (await this.persistence.hasPendingIntent())) {
      throw new SynchronizationPausedError();
    }
    this.prepared = { ...boundary };
  }

  async requestContext(): Promise<BindingRequestContext> {
    const attempt = this.prepared;
    if (!attempt) throw new SynchronizationPausedError();
    const snapshot = await this.persistence.snapshot();
    const binding = await this.requireReady(snapshot);
    if (
      snapshot.localDataSetId !== attempt.localDataSetId ||
      snapshot.exchange.exchangeFence !== attempt.exchangeFence
    ) {
      throw new SynchronizationOwnershipLostError();
    }
    const capabilities = await this.metadata(() => this.transport.capabilities(), snapshot);
    if (
      capabilities.serverInstanceId !== binding.serverInstanceId ||
      capabilities.accessMode !== binding.identity.accessMode
    ) {
      await this.pause(snapshot, 'binding_mismatch');
    }
    if (
      binding.boundary.kind !== 'generationless' ||
      !capabilities.supportedBoundaries.includes('generationless')
    ) {
      await this.pause(snapshot, 'reconciliation_required');
    }
    const cached = this.cachedContext(attempt, binding);
    if (cached) {
      await this.reobserve(snapshot, cached.expectation);
      await this.assertCurrent(cached);
      return cached;
    }
    let expectation: string;
    let expiresAt: number;
    if (
      snapshot.initial?.phase === 'confirmed' &&
      snapshot.initial.expectation !== null &&
      snapshot.initial.expiresAt !== null
    ) {
      expectation = snapshot.initial.expectation;
      await this.reobserve(snapshot, expectation);
      expiresAt = snapshot.initial.expiresAt;
    } else {
      const started = this.clock.now();
      let observation = await this.metadata(() => this.transport.bootstrap(), snapshot);
      await this.requireObservation(snapshot, observation);
      if (observation.population === 'empty') {
        observation = await this.metadata(
          () =>
            this.transport.confirm({
              expectation: observation.expectation,
              expectedPopulation: 'empty',
            }),
          snapshot,
        );
        await this.requireObservation(snapshot, observation);
      }
      expectation = observation.expectation;
      expiresAt = started + 10 * 60_000;
    }
    const context = { binding, exchangeFence: snapshot.exchange.exchangeFence, expectation };
    await this.assertCurrent(context);
    this.cached = { context, expiresAt, fencingToken: attempt.fencingToken };
    return context;
  }

  private async reobserve(snapshot: LocalBindingSnapshot, expectation: string): Promise<void> {
    const observation = await this.metadata(() => this.transport.bootstrap(expectation), snapshot);
    await this.requireObservation(snapshot, observation);
    if (observation.expectation !== expectation) {
      await this.pause(snapshot, 'reconciliation_required');
    }
  }

  private cachedContext(
    attempt: ExchangeAttemptBoundary,
    binding: LocalServerBinding,
  ): BindingRequestContext | undefined {
    const cached = this.cached;
    return cached &&
      cached.fencingToken === attempt.fencingToken &&
      cached.expiresAt > this.clock.now() &&
      sameBinding(cached.context.binding, binding) &&
      cached.context.exchangeFence === attempt.exchangeFence
      ? cached.context
      : undefined;
  }

  async assertCurrent(
    context: Pick<BindingRequestContext, 'binding' | 'exchangeFence'>,
  ): Promise<void> {
    const snapshot = await this.persistence.snapshot();
    const binding = await this.requireReady(snapshot);
    if (
      snapshot.exchange.exchangeFence !== context.exchangeFence ||
      !sameBinding(binding, context.binding)
    ) {
      throw new SynchronizationOwnershipLostError();
    }
  }

  async denyInitial(
    context: Pick<BindingRequestContext, 'binding' | 'exchangeFence'>,
  ): Promise<void> {
    const snapshot = await this.persistence.snapshot();
    if (
      snapshot.exchange.exchangeFence !== context.exchangeFence ||
      !snapshot.binding ||
      !sameBinding(snapshot.binding, context.binding)
    ) {
      throw new SynchronizationOwnershipLostError();
    }
    this.cached = undefined;
    if (snapshot.initial) await this.pause(snapshot, 'reconciliation_required');
  }

  async denyAccess(
    context: Pick<BindingRequestContext, 'binding' | 'exchangeFence'>,
    code: AccessError['code'],
  ): Promise<void> {
    if (code === 'BINDING_PRECONDITION_FAILED') {
      await this.denyInitial(context);
      return;
    }
    await this.assertCurrent(context);
    const state = code === 'AUTHENTICATION_REQUIRED' ? 'auth_required' : 'disconnected';
    this.cached = undefined;
    await this.persistence.pause(state, context.exchangeFence);
    throw new SynchronizationOwnershipLostError();
  }

  private async metadata<T>(request: () => Promise<T>, snapshot: LocalBindingSnapshot): Promise<T> {
    try {
      return await request();
    } catch (error) {
      if (error instanceof SynchronizationAccessError && snapshot.binding) {
        await this.denyAccess(
          { binding: snapshot.binding, exchangeFence: snapshot.exchange.exchangeFence },
          error.body.code,
        );
        throw new SynchronizationUnavailableError();
      }
      throw error;
    }
  }

  private async requireReady(snapshot: LocalBindingSnapshot): Promise<LocalServerBinding> {
    if (
      snapshot.exchange.state !== 'bound' ||
      !snapshot.binding ||
      snapshot.initial?.phase === 'uncertain'
    ) {
      throw new SynchronizationPausedError();
    }
    if (
      snapshot.initial?.expiresAt !== undefined &&
      snapshot.initial.expiresAt !== null &&
      snapshot.initial.expiresAt <= this.clock.now()
    ) {
      await this.pause(snapshot, 'reconciliation_required');
    }
    return snapshot.binding;
  }

  private async requireObservation(
    snapshot: LocalBindingSnapshot,
    observation: SynchronizationBootstrap,
  ): Promise<void> {
    const observed = parseAccessContract('LocalServerBinding', {
      version: '1',
      localDataSetId: snapshot.localDataSetId,
      serverOrigin: this.transport.serverOrigin,
      serverInstanceId: observation.serverInstanceId,
      identity: observation.identity,
      boundary: observation.boundary,
    });
    if (!snapshot.binding || !sameBinding(snapshot.binding, observed))
      await this.pause(snapshot, 'binding_mismatch');
  }

  private async pause(
    snapshot: LocalBindingSnapshot,
    state: 'binding_mismatch' | 'reconciliation_required',
  ): Promise<never> {
    this.cached = undefined;
    await this.persistence.pause(state, snapshot.exchange.exchangeFence);
    throw new SynchronizationOwnershipLostError();
  }
}
