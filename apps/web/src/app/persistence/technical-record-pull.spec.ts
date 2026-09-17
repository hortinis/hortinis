import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { afterEach, describe, expect, it } from 'vitest';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import type { ChangePage } from '../sync/conformance';

describe('technical record pull persistence', () => {
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

  it('applies ordered changes and advances the cursor atomically', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);

    await persistence.commitPulledPage(page('first value', '1', 'opaque-one'));
    await persistence.commitPulledPage(page('second value', '2', 'opaque-two'));

    await expect(database.technicalRecords.toArray()).resolves.toEqual([
      { recordId: recordId(), value: 'second value', lastAcceptedRevision: '2' },
    ]);
    await expect(persistence.synchronizationCursor()).resolves.toBe('opaque-two');
  });

  it('does not overwrite local intent while applying a remote change', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    await database.technicalRecords.add({
      recordId: recordId(),
      value: 'local value',
      lastAcceptedRevision: '1',
    });
    await database.outboxOperations.add({
      operationId: operationId(),
      recordId: recordId(),
      value: 'local value',
      kind: 'replace',
      expectedRevision: '1',
    });

    await persistence.commitPulledPage(page('remote value', '2', 'opaque-two'));

    await expect(database.technicalRecords.get(recordId())).resolves.toEqual({
      recordId: recordId(),
      value: 'local value',
      lastAcceptedRevision: '2',
    });
  });

  it('rejects unordered pages without advancing the cursor', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    await persistence.commitPulledPage(page('first value', '1', 'opaque-one'));

    const invalid: ChangePage = {
      changes: [
        change('second value', '3', operationIdTwo()),
        change('older value', '2', operationIdThree()),
      ],
      nextCursor: 'opaque-three',
      hasMore: false,
    };
    await expect(persistence.commitPulledPage(invalid)).rejects.toThrow('ascending');
    await expect(persistence.synchronizationCursor()).resolves.toBe('opaque-one');
  });

  function openDatabase(): HortinisDatabase {
    const database = new HortinisDatabase(`hortinis-e5-${crypto.randomUUID()}`);
    databases.push(database);
    return database;
  }

  function persistenceFor(database: HortinisDatabase): TechnicalRecordPersistence {
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    return TestBed.inject(TechnicalRecordPersistence);
  }
});

function page(value: string, revision: string, nextCursor: string): ChangePage {
  return { changes: [change(value, revision, operationId())], nextCursor, hasMore: false };
}

function change(value: string, revision: string, operation: string) {
  return {
    operationId: operation,
    record: { recordId: recordId(), revision, value },
    sequence: revision,
  };
}

function operationId(): string {
  return '01890f3e-7c5a-7b12-8abc-0123456789ab';
}

function recordId(): string {
  return '01890f3e-7c5a-7b13-8abc-0123456789ab';
}

function operationIdTwo(): string {
  return '01890f3e-7c5a-7b14-8abc-0123456789ab';
}

function operationIdThree(): string {
  return '01890f3e-7c5a-7b15-8abc-0123456789ab';
}
