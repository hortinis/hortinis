import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { equalTechnicalRecordOperations, isTechnicalRecordOperation } from './conformance';
import type { TechnicalRecord } from './conformance';

interface Fixture {
  id: string;
  description: string;
  request?: unknown;
  equivalentRequest?: unknown;
  response?: unknown;
  state?: {
    records: TechnicalRecord[];
    acceptedOperations?: { operationId: string; request: unknown }[];
  };
  expected: {
    valid: boolean;
    errorCode?: string;
    equivalent?: boolean;
    response?: unknown;
  };
}

const fixtureNames = [
  'change-page.json',
  'create-accepted.json',
  'invalid-change-cursor.json',
  'invalid-identifiers-and-revision.json',
  'invalid-operation-shape.json',
  'invalid-request.json',
  'operation-id-reused.json',
  'record-already-exists.json',
  'record-not-found.json',
  'replay-equivalent.json',
  'replace-accepted.json',
  'revision-conflict.json',
];

describe('technical synchronization conformance fixtures', () => {
  it('consumes every shared scenario and agrees with its expected validity', () => {
    for (const fixture of loadFixtures()) {
      if (isObject(fixture.request) && 'kind' in fixture.request) {
        expect(isTechnicalRecordOperation(fixture.request)).toBe(fixture.expected.valid);
      } else if (fixture.expected.errorCode) {
        expect(fixture.expected.valid).toBe(false);
      }

      if (fixture.equivalentRequest) {
        expect(equalTechnicalRecordOperations(fixture.request, fixture.equivalentRequest)).toBe(
          fixture.expected.equivalent,
        );
      }
      if (fixture.state?.acceptedOperations && isObject(fixture.request)) {
        const request = fixture.request;
        const receipt = fixture.state.acceptedOperations.find(
          (operation) => operation.operationId === request['operationId'],
        );
        if (receipt) {
          expect(equalTechnicalRecordOperations(request, receipt.request)).toBe(false);
        }
      }
    }
  });

  it('keeps declarative server state and expected protocol responses well formed', () => {
    for (const fixture of loadFixtures()) {
      if (fixture.state) {
        expect(Array.isArray(fixture.state.records)).toBe(true);
        for (const record of fixture.state.records) {
          expect(typeof record.recordId).toBe('string');
          expect(typeof record.revision).toBe('string');
          expect(typeof record.value).toBe('string');
        }
      }
      if (fixture.expected.response) {
        expect(typeof fixture.expected.response).toBe('object');
        if (fixture.expected.errorCode) {
          expect(isObject(fixture.expected.response)).toBe(true);
          expect((fixture.expected.response as Record<string, unknown>)['code']).toBe(
            fixture.expected.errorCode,
          );
        }
      }
      if (fixture.response) {
        expect(typeof fixture.response).toBe('object');
      }
    }
  });
});

function loadFixtures(): Fixture[] {
  const fixtureDirectory = join(__dirname, '../../../../../contracts/sync/fixtures');
  return fixtureNames.map(
    (name) => JSON.parse(readFileSync(join(fixtureDirectory, name), 'utf8')) as Fixture,
  );
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
