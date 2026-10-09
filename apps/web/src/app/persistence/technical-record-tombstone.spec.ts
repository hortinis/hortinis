import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { afterEach, describe, expect, it } from 'vitest';
import type {
  ChangePage,
  CreateTechnicalRecordOperation,
  DeleteTechnicalRecordOperation,
  TombstoneOperationResult,
} from '../sync/conformance';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';

const recordId = '01890f3e-7c5a-7b13-8abc-0123456789ab';
const createId = '01890f3e-7c5a-7b12-8abc-0123456789ab';
const deleteId = '01890f3e-7c5a-7b16-8abc-0123456789ab';
const remoteId = '01890f3e-7c5a-7b17-8abc-0123456789ab';
const replaceId = '01890f3e-7c5a-7b18-8abc-0123456789ab';

describe('technical record tombstones', () => {
  const databases: Dexie[] = [];
  afterEach(async () => {
    TestBed.resetTestingModule();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  function open(name = `hortinis-g2c-${crypto.randomUUID()}`): HortinisDatabase {
    const database = new HortinisDatabase(name);
    databases.push(database);
    return database;
  }

  function persistence(database: HortinisDatabase): TechnicalRecordPersistence {
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    return TestBed.inject(TechnicalRecordPersistence);
  }

  function acceptedDelete(revision = '2', sequence = '41'): TombstoneOperationResult {
    return {
      outcome: 'accepted',
      operationId: deleteId,
      tombstone: { recordId, revision, deletedAtSequence: sequence },
      sequence,
    };
  }

  function tombstonePage(cursor = 'after-delete', revision = '2'): ChangePage {
    return {
      changes: [
        {
          operationId: remoteId,
          tombstone: { recordId, revision, deletedAtSequence: '41' },
          sequence: '41',
        },
      ],
      nextCursor: cursor,
      hasMore: false,
    };
  }

  it('atomically hides a local deletion while retaining its base and stable outbox operation', async () => {
    const database = open();
    const store = persistence(database);
    const record = { recordId, value: 'original', lastAcceptedRevision: '1' };
    await database.technicalRecords.add(record);
    const operation = await store.commitDelete(deleteId, recordId);
    expect(operation).toEqual({
      operationId: deleteId,
      recordId,
      kind: 'delete',
      expectedRevision: '1',
    });
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.pendingDeletionRecords.get(recordId)).resolves.toEqual(record);
    await expect(database.outboxOperations.get(deleteId)).resolves.toEqual(operation);

    await expect(store.commitDelete(replaceId, recordId)).rejects.toThrow('missing');
    await expect(database.outboxOperations.count()).resolves.toBe(1);
  });

  it('restores a pending deletion and its unchanged operation after reopening', async () => {
    const name = `hortinis-g2c-${crypto.randomUUID()}`;
    const first = open(name);
    const store = persistence(first);
    await first.technicalRecords.add({ recordId, value: 'old', lastAcceptedRevision: '1' });
    const operation = await store.commitDelete(deleteId, recordId);
    first.close();
    TestBed.resetTestingModule();

    const reopened = open(name);
    const resumed = persistence(reopened);
    await expect(resumed.firstPendingOperation()).resolves.toEqual(operation);
    await expect(reopened.pendingDeletionRecords.get(recordId)).resolves.toMatchObject({
      value: 'old',
      lastAcceptedRevision: '1',
    });
    await resumed.commitAcceptedResult(
      operation as DeleteTechnicalRecordOperation,
      acceptedDelete(),
    );
    await expect(reopened.technicalTombstones.get(recordId)).resolves.toEqual(
      acceptedDelete().tombstone,
    );
  });

  it('rolls back the local deletion when its outbox write fails', async () => {
    const database = open();
    const store = persistence(database);
    const record = { recordId, value: 'original', lastAcceptedRevision: '1' };
    await database.technicalRecords.add(record);
    await database.outboxOperations.add({
      operationId: deleteId,
      recordId: remoteId,
      kind: 'create',
      value: 'unrelated',
    });
    await expect(store.commitDelete(deleteId, recordId)).rejects.toBeDefined();
    await expect(database.technicalRecords.get(recordId)).resolves.toEqual(record);
    await expect(database.pendingDeletionRecords.count()).resolves.toBe(0);
  });

  it('promotes a dependent deletion and commits its accepted tombstone atomically', async () => {
    const database = open();
    const store = persistence(database);
    const create: CreateTechnicalRecordOperation = {
      operationId: createId,
      recordId,
      kind: 'create',
      value: 'new',
    };
    await store.commitCreate(create);
    await database.outboxOperations.update(create.operationId, { submittedAt: 0 });
    await store.commitDelete(deleteId, recordId);
    await expect(store.firstPendingOperation()).resolves.toEqual(create);
    await store.commitAcceptedResult(create, {
      outcome: 'accepted',
      operationId: createId,
      record: { recordId, revision: '1', value: 'new' },
      sequence: '40',
    });
    const deleteOperation = await store.firstPendingOperation();
    expect(deleteOperation).toEqual({
      operationId: deleteId,
      recordId,
      kind: 'delete',
      expectedRevision: '1',
    });
    await database.outboxOperations.put({
      operationId: deleteId,
      recordId,
      kind: 'delete',
      expectedRevision: '1',
      predecessorOperationId: createId,
      submittedAt: 0,
    });
    await store.commitAcceptedResult(deleteOperation!, acceptedDelete());
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.pendingDeletionRecords.count()).resolves.toBe(0);
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.technicalTombstones.get(recordId)).resolves.toEqual(
      acceptedDelete().tombstone,
    );
    await expect(database.acceptedOperationResults.get(deleteId)).resolves.toEqual(
      acceptedDelete(),
    );
  });

  it('rolls back a deletion receipt when its pending base is missing', async () => {
    const database = open();
    const store = persistence(database);
    await database.technicalRecords.add({ recordId, value: 'old', lastAcceptedRevision: '1' });
    const operation = await store.commitDelete(deleteId, recordId);
    await database.pendingDeletionRecords.delete(recordId);
    await expect(
      store.commitAcceptedResult(operation as DeleteTechnicalRecordOperation, acceptedDelete()),
    ).rejects.toThrow('pending deletion record');
    await expect(database.acceptedOperationResults.count()).resolves.toBe(0);
    await expect(database.technicalTombstones.count()).resolves.toBe(0);
    await expect(database.outboxOperations.get(deleteId)).resolves.toEqual(operation);
  });

  it('applies a mixed page and ignores a stale live change after the tombstone', async () => {
    const database = open();
    const store = persistence(database);
    await store.commitPulledPage({
      changes: [
        {
          operationId: createId,
          record: { recordId, revision: '1', value: 'remote' },
          sequence: '40',
        },
        {
          operationId: remoteId,
          tombstone: { recordId, revision: '2', deletedAtSequence: '41' },
          sequence: '41',
        },
      ],
      nextCursor: 'after-delete',
      hasMore: false,
    });
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.technicalTombstones.get(recordId)).resolves.toMatchObject({
      revision: '2',
    });
    await store.commitPulledPage(tombstonePage('repeated'));
    await store.commitPulledPage({
      changes: [
        {
          operationId: createId,
          record: { recordId, revision: '1', value: 'stale' },
          sequence: '40',
        },
      ],
      nextCursor: 'stale-replayed',
      hasMore: false,
    });
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(store.synchronizationCursor()).resolves.toBe('stale-replayed');
  });

  it('retains an overlapping replacement as a durable deletion conflict', async () => {
    const name = `hortinis-g2c-${crypto.randomUUID()}`;
    const database = open(name);
    const store = persistence(database);
    await database.technicalRecords.add({
      recordId,
      value: 'local edit',
      lastAcceptedRevision: '1',
    });
    const replacement = {
      operationId: replaceId,
      recordId,
      kind: 'replace' as const,
      value: 'local edit',
      expectedRevision: '1',
    };
    await database.outboxOperations.add(replacement);
    await store.commitPulledPage(tombstonePage());
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.outboxOperations.get(replaceId)).resolves.toEqual(replacement);
    await expect(store.firstPendingOperation()).resolves.toBeUndefined();
    database.close();
    const reopened = open(name);
    await expect(reopened.deletionConflicts.get(replaceId)).resolves.toMatchObject({
      reason: 'remote-deletion',
      tombstone: { recordId, revision: '2' },
      localRecord: { value: 'local edit' },
    });
    await expect(reopened.outboxOperations.get(replaceId)).resolves.toEqual(replacement);
  });

  it('rolls back every page write and its cursor when a later change cannot apply', async () => {
    const database = open();
    const store = persistence(database);
    await store.commitPulledPage({ changes: [], nextCursor: 'before-page', hasMore: false });
    const invalid: ChangePage = {
      changes: [
        {
          operationId: createId,
          record: { recordId, revision: '4', value: 'transient' },
          sequence: '40',
        },
        {
          operationId: remoteId,
          tombstone: { recordId, revision: '3', deletedAtSequence: '41' },
          sequence: '41',
        },
      ],
      nextCursor: 'after-page',
      hasMore: false,
    };
    await expect(store.commitPulledPage(invalid)).rejects.toThrow('precedes');
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.technicalTombstones.count()).resolves.toBe(0);
    await expect(store.synchronizationCursor()).resolves.toBe('before-page');
  });

  it('turns a pulled acknowledgement into a stable accepted result', async () => {
    const database = open();
    const store = persistence(database);
    await database.technicalRecords.add({ recordId, value: 'old', lastAcceptedRevision: '1' });
    const operation = await store.commitDelete(deleteId, recordId);
    expect('kind' in operation && operation.kind).toBe('delete');
    await store.commitPulledPage({
      changes: [{ operationId: deleteId, tombstone: acceptedDelete().tombstone, sequence: '41' }],
      nextCursor: 'after-own-delete',
      hasMore: false,
    });
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(deleteId)).resolves.toEqual(
      acceptedDelete(),
    );
    await expect(database.pendingDeletionRecords.count()).resolves.toBe(0);
    await expect(
      store.commitAcceptedResult(operation as DeleteTechnicalRecordOperation, acceptedDelete()),
    ).resolves.toBeUndefined();
  });

  it('does not resurrect a tombstone when an older accepted live result arrives later', async () => {
    const database = open();
    const store = persistence(database);
    const create: CreateTechnicalRecordOperation = {
      operationId: createId,
      recordId,
      kind: 'create',
      value: 'local',
    };
    await store.commitCreate(create);
    await store.commitPulledPage(tombstonePage());
    await store.commitAcceptedResult(create, {
      outcome: 'accepted',
      operationId: createId,
      record: { recordId, revision: '1', value: 'local' },
      sequence: '40',
    });
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.technicalTombstones.get(recordId)).resolves.toMatchObject({
      revision: '2',
    });
    await expect(database.outboxOperations.count()).resolves.toBe(0);
  });
});
