import { afterEach, describe, expect, it } from 'vitest';
import type { OperationResult, TechnicalRecordOperation } from '../sync/conformance';
import { SynchronizationUnavailableError } from '../sync/synchronization-transport';
import {
  emptyPage,
  SynchronizationTestHarness,
  testOperation,
} from '../sync/testing/synchronization-test-harness';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';

interface Intent {
  id: string;
  kind: 'create' | 'replace' | 'delete';
  value?: string;
  frozen?: TechnicalRecordOperation;
}

describe('deterministic outbox model sequences', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    await harness.close();
  });

  it.each(Array.from({ length: 12 }, (_, index) => index + 1))(
    'matches retained intent and immutable server replay for seed %s',
    async (seed) => {
      harness = new SynchronizationTestHarness();
      let random = seed;
      const choice = () => {
        random = (Math.imul(random, 1664525) + 1013904223) >>> 0;
        return random % 6;
      };
      const queue: Intent[] = [
        { id: testOperation.operationId, kind: 'create', value: testOperation.value },
      ];
      const receipts = new Map<
        string,
        { operation: TechnicalRecordOperation; result: OperationResult }
      >();
      const lost = new Set<string>();
      let lose = false;
      const transport = {
        async submitOperation(operation: TechnicalRecordOperation): Promise<OperationResult> {
          const head = queue[0];
          expect(head).toBeDefined();
          expect(operation.operationId).toBe(head.id);
          expect(operation.kind).toBe(head.kind);
          if (operation.kind !== 'delete') expect(operation.value).toBe(head.value);
          if (head.frozen) expect(operation).toEqual(head.frozen);
          else head.frozen = structuredClone(operation);
          let receipt = receipts.get(head.id);
          if (receipt) expect(operation).toEqual(receipt.operation);
          else {
            if (operation.kind !== 'create')
              expect(operation.expectedRevision).toBe(String(receipts.size));
            const revision = String(receipts.size + 1);
            const result: OperationResult =
              operation.kind === 'delete'
                ? {
                    outcome: 'accepted',
                    operationId: operation.operationId,
                    sequence: revision,
                    tombstone: {
                      recordId: operation.recordId,
                      revision,
                      deletedAtSequence: revision,
                    },
                  }
                : {
                    outcome: 'accepted',
                    operationId: operation.operationId,
                    sequence: revision,
                    record: { recordId: operation.recordId, revision, value: operation.value },
                  };
            receipt = { operation: structuredClone(operation), result };
            receipts.set(head.id, receipt);
          }
          if (lose && !lost.has(head.id)) {
            lost.add(head.id);
            throw new SynchronizationUnavailableError();
          }
          return receipt.result;
        },
        pullChanges: async () => emptyPage,
      };
      let owner = harness.scope(transport);
      await owner.persistence.commitCreate(testOperation);
      let desiredValue = testOperation.value;
      let deleted = false;
      async function push() {
        const outcome = await owner.service.pushOnePendingOperation();
        if (outcome.status === 'accepted') queue.shift();
        else expect(['empty', 'failed']).toContain(outcome.status);
      }
      for (let step = 0; step < 40 && !deleted; step++) {
        const action = choice();
        if (action < 2) {
          desiredValue = `seed ${seed}, edit ${step}`;
          const id = `00000000-0000-4000-8000-${(seed * 100 + step + 10).toString(16).padStart(12, '0')}`;
          const tail = queue.at(-1);
          if (tail && !tail.frozen) tail.value = desiredValue;
          else queue.push({ id, kind: 'replace', value: desiredValue });
          await owner.persistence.commitReplace(id, testOperation.recordId, desiredValue);
        } else if (action < 4) {
          lose = action === 3;
          await push();
        } else if (action === 4) {
          owner.destroy();
          harness.database.close();
          await harness.database.open();
          owner = harness.scope(transport);
        } else {
          const latest = [...receipts.values()].at(-1)?.result;
          if (latest && 'record' in latest) {
            await owner.persistence.commitPulledPage({
              changes: [
                {
                  operationId: latest.operationId,
                  record: latest.record,
                  sequence: latest.sequence,
                },
              ],
              nextCursor: `cursor-${step}`,
              hasMore: false,
            });
          }
        }
        const rows = await harness.database.outboxOperations.toArray();
        expect(rows.length).toBe(queue.length);
        for (const intent of queue) {
          const row = rows.find((operation) => operation.operationId === intent.id);
          expect(row?.kind).toBe(intent.kind);
          if (row && row.kind !== 'delete') expect(row.value).toBe(intent.value);
          if (intent.frozen && row)
            expect(toSubmittedTechnicalRecordOperation(row)).toEqual(intent.frozen);
        }
        expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
          value: desiredValue,
        });
        if (step === 39 || (seed % 3 === 0 && step === 27)) {
          const id = crypto.randomUUID();
          const tail = queue.at(-1);
          if (tail?.kind === 'create' && !tail.frozen) queue.pop();
          else if (tail && !tail.frozen) {
            tail.kind = 'delete';
            delete tail.value;
          } else queue.push({ id, kind: 'delete' });
          await owner.persistence.commitDelete(id, testOperation.recordId);
          deleted = true;
        }
      }
      lose = false;
      while (queue.length) await push();
      expect(await harness.database.outboxOperations.count()).toBe(0);
      expect(await harness.database.acceptedOperationResults.count()).toBe(receipts.size);
      if (deleted)
        expect(await harness.database.technicalRecords.get(testOperation.recordId)).toBeUndefined();
      else
        expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
          value: desiredValue,
        });
    },
  );
});
