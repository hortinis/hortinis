import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';
import type { OperationResult, TechnicalRecordOperation } from './conformance';
import type { SynchronizationTransport } from './synchronization-transport';
import type { NetworkStatus } from './network-status';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { NETWORK_STATUS } from './network-status';

describe('TechnicalRecordSynchronizationService', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    TestBed.resetTestingModule();
    await Promise.all(
      databases.splice(0).map(async (database) => {
        database.close();
        await database.delete();
      }),
    );
  });

  it('pushes one pending operation and commits its stable result', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const result = acceptedResult(operation);
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => result),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    expect(transport.submitOperation).toHaveBeenCalledOnce();
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(operation.operationId)).resolves.toEqual(
      result,
    );
  });

  it('skips the request while offline and retains pending work', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(false));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toEqual({ status: 'offline' });
    expect(transport.submitOperation).not.toHaveBeenCalled();
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
  });

  it('retains pending work when transport or result persistence fails', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new Error('service unavailable');
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
  });

  it('retries the same operation after a lost acknowledgement', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const result = acceptedResult(operation);
    let attempts = 0;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (submitted) => {
        attempts += 1;
        expect(submitted).toEqual(operation);
        if (attempts === 1) {
          throw new Error('acknowledgement lost');
        }
        return result;
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({
      status: 'accepted',
      result,
    });
    expect(transport.submitOperation).toHaveBeenCalledTimes(2);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(operation.operationId)).resolves.toEqual(
      result,
    );
  });

  function openDatabase(): HortinisDatabase {
    const database = new HortinisDatabase(`hortinis-e3-${crypto.randomUUID()}`);
    databases.push(database);
    return database;
  }

  function services(
    database: HortinisDatabase,
    transport: SynchronizationTransport,
    network: NetworkStatus,
  ): { persistence: TechnicalRecordPersistence; service: TechnicalRecordSynchronizationService } {
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: network },
      ],
    });
    return {
      persistence: TestBed.inject(TechnicalRecordPersistence),
      service: TestBed.inject(TechnicalRecordSynchronizationService),
    };
  }
});

function createOperation(): TechnicalRecordOperation & { kind: 'create' } {
  return {
    operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    value: 'first value',
    kind: 'create',
  };
}

function acceptedResult(operation: TechnicalRecordOperation): OperationResult {
  return {
    outcome: 'accepted',
    operationId: operation.operationId,
    record: { recordId: operation.recordId, revision: '1', value: operation.value },
    sequence: '1',
  };
}

function onlineStatus(isOnline: boolean): NetworkStatus {
  return { isOnline: () => isOnline };
}
