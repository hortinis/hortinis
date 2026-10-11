import { inject, Injectable } from '@angular/core';
import type { Table } from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { LocalDataSetUnavailableError } from './local-data-set-identity';
import {
  readLocalBinding,
  sameBinding,
  readLocalInitialAdmission,
  type LocalInitialAdmission,
} from './local-exchange-metadata';
import {
  assertSynchronizationOwnership,
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from './synchronization-ownership';
import {
  parseAccessContract,
  type LocalDataSetId,
  type LocalServerBinding,
  type LocalExchangeState,
  type LocalExchangeStatus,
} from '../sync/access-contracts.rule';

export interface LocalBindingSnapshot {
  readonly localDataSetId: LocalDataSetId;
  readonly exchange: LocalExchangeState;
  readonly binding: LocalServerBinding | undefined;
  readonly initial: LocalInitialAdmission | undefined;
}

@Injectable({ providedIn: 'root' })
export class LocalBindingExchangePersistence {
  private readonly database = inject(HortinisDatabase);

  snapshot(): Promise<LocalBindingSnapshot> {
    return this.database.transaction('r', this.tables(), () => this.read());
  }

  async hasPendingIntent(): Promise<boolean> {
    return this.database.transaction(
      'r',
      this.database.localDataSetIdentity,
      this.database.localExchangeState,
      this.database.outboxOperations,
      async () => {
        const owner = await this.database.requireLocalDataSetId();
        await this.database.requireLocalExchangeState(owner);
        return (await this.database.outboxOperations.count()) > 0;
      },
    );
  }

  classifyUnbound(): Promise<'empty' | 'standalone' | 'existing-server-evidence'> {
    return this.database.transaction('r', this.database.tables, async () => {
      const owner = await this.database.requireLocalDataSetId();
      await this.database.requireLocalExchangeState(owner);
      const history = [
        this.database.acceptedOperationResults,
        this.database.acceptedTechnicalRecords,
        this.database.technicalTombstones,
        this.database.synchronizationState,
        this.database.synchronizationRetryState,
        this.database.revisionConflicts,
        this.database.deletionConflicts,
        this.database.rejectedOperations,
      ];
      for (const table of history) {
        if ((await table.count()) > 0) return 'existing-server-evidence';
      }
      const operations = await this.database.outboxOperations.toArray();
      const records = [
        ...(await this.database.technicalRecords.toArray()),
        ...(await this.database.pendingDeletionRecords.toArray()),
      ];
      if (
        operations.some(
          (operation) =>
            operation.submittedAt !== undefined ||
            operation.legacySubmissionUnknown ||
            (operation.kind !== 'create' && !('predecessorOperationId' in operation)),
        ) ||
        records.some((record) => record.lastAcceptedRevision !== null)
      )
        return 'existing-server-evidence';
      return operations.length === 0 && records.length === 0 ? 'empty' : 'standalone';
    });
  }

  begin(ownership: SynchronizationOwnership): Promise<void> {
    return this.database.transaction('rw', this.tables(), async () => {
      await assertSynchronizationOwnership(this.database, ownership);
      const before = await this.read();
      if (
        before.exchange.logoutPending ||
        (before.binding && before.initial?.phase !== 'uncertain')
      ) {
        throw new SynchronizationOwnershipLostError();
      }
      await this.database.localExchangeState.put({
        localDataSetId: before.localDataSetId,
        value: {
          state: 'binding_pending',
          exchangeFence: before.exchange.exchangeFence,
          logoutPending: false,
        },
      });
    });
  }

  confirm(
    binding: LocalServerBinding,
    expectation: string,
    expiresAt: number,
    ownership: SynchronizationOwnership,
  ): Promise<void> {
    const value = parseAccessContract('LocalServerBinding', binding);
    const token = parseAccessContract('ExchangeExpectation', expectation);
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= ownership.now()) {
      return Promise.reject(new LocalDataSetUnavailableError());
    }
    return this.database.transaction('rw', this.tables(), async () => {
      await assertSynchronizationOwnership(this.database, ownership);
      const before = await this.read();
      if (
        value.localDataSetId !== before.localDataSetId ||
        before.exchange.state !== 'binding_pending' ||
        (before.binding && !sameBinding(before.binding, value))
      ) {
        throw new SynchronizationOwnershipLostError();
      }
      const fence = (BigInt(before.exchange.exchangeFence) + 1n).toString();
      await this.database.localServerBindings.put(value);
      await this.database.localExchangeState.put({
        localDataSetId: before.localDataSetId,
        value: { state: 'bound', exchangeFence: fence, logoutPending: false },
      });
      await this.database.localInitialAdmissions.put({
        localDataSetId: before.localDataSetId,
        exchangeFence: fence,
        phase: 'confirmed',
        expectation: token,
        expiresAt,
      });
      await this.invalidateLease(fence);
    });
  }

  pause(
    state: Exclude<LocalExchangeStatus, 'bound' | 'binding_pending'>,
    expectedFence: string,
    expiry?: { expectation: string; now: number },
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    return this.database.transaction('rw', this.tables(), async () => {
      if (ownership) await assertSynchronizationOwnership(this.database, ownership);
      const before = await this.read();
      if (before.exchange.exchangeFence !== expectedFence)
        throw new SynchronizationOwnershipLostError();
      if (
        expiry &&
        (before.initial?.phase !== 'confirmed' ||
          before.initial.expectation !== expiry.expectation ||
          before.initial.expiresAt === null ||
          before.initial.expiresAt > expiry.now)
      )
        return;
      const fence = (BigInt(expectedFence) + 1n).toString();
      await this.database.localExchangeState.put({
        localDataSetId: before.localDataSetId,
        value: parseAccessContract('LocalExchangeState', {
          state: before.exchange.logoutPending ? 'auth_required' : state,
          exchangeFence: fence,
          logoutPending: before.exchange.logoutPending,
        }),
      });
      if (before.initial) {
        await this.database.localInitialAdmissions.put({
          localDataSetId: before.localDataSetId,
          exchangeFence: fence,
          phase: 'uncertain',
          expectation: null,
          expiresAt: null,
        });
      }
      await this.invalidateLease(fence);
    });
  }

  resume(binding: LocalServerBinding, ownership: SynchronizationOwnership): Promise<void> {
    const value = parseAccessContract('LocalServerBinding', binding);
    return this.database.transaction('rw', this.tables(), async () => {
      await assertSynchronizationOwnership(this.database, ownership);
      const before = await this.read();
      if (
        before.exchange.logoutPending ||
        !before.binding ||
        before.initial ||
        !sameBinding(before.binding, value)
      ) {
        throw new SynchronizationOwnershipLostError();
      }
      const fence = (BigInt(before.exchange.exchangeFence) + 1n).toString();
      await this.database.localExchangeState.put({
        localDataSetId: before.localDataSetId,
        value: { state: 'bound', exchangeFence: fence, logoutPending: false },
      });
      await this.invalidateLease(fence);
    });
  }

  private async read(): Promise<LocalBindingSnapshot> {
    const localDataSetId = await this.database.requireLocalDataSetId();
    const exchange = await this.database.requireLocalExchangeState(localDataSetId);
    const binding = readLocalBinding(
      await this.database.localServerBindings.toArray(),
      localDataSetId,
    );
    const initial = readLocalInitialAdmission(
      await this.database.localInitialAdmissions.toArray(),
      localDataSetId,
      exchange.exchangeFence,
    );
    if (
      binding &&
      !initial &&
      (exchange.state === 'offline' ||
        exchange.state === 'binding_pending' ||
        exchange.exchangeFence === '1')
    )
      throw new LocalDataSetUnavailableError();
    if ((exchange.state === 'bound' || initial) && !binding)
      throw new LocalDataSetUnavailableError();
    return { localDataSetId, exchange, binding, initial };
  }

  private async invalidateLease(exchangeFence: string): Promise<void> {
    const lease = await this.database.synchronizationLeases.get('technical-records');
    if (lease)
      await this.database.synchronizationLeases.put({ ...lease, exchangeFence, expiresAt: 0 });
  }

  private tables(): Table[] {
    return [
      this.database.localDataSetIdentity,
      this.database.localServerBindings,
      this.database.localExchangeState,
      this.database.localInitialAdmissions,
      this.database.synchronizationLeases,
    ];
  }
}
