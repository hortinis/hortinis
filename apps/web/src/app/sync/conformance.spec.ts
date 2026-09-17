import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  equalTechnicalRecordOperations,
  isChangePage,
  isOperationResult,
  isSynchronizationError,
  isSyncCursor,
  isTechnicalRecord,
  isTechnicalRecordOperation,
} from './conformance';
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
    canonicalRequest?: string;
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
      expect(fixture.description).not.toBe('');
      if (isObject(fixture.request) && 'kind' in fixture.request) {
        expect(isTechnicalRecordOperation(fixture.request)).toBe(fixture.expected.valid);
        if (fixture.expected.valid && fixture.expected.canonicalRequest) {
          expect(canonicalJson(fixture.request)).toBe(fixture.expected.canonicalRequest);
        }
      } else if (isObject(fixture.request) && 'cursor' in fixture.request) {
        expect(isSyncCursor(fixture.request['cursor'])).toBe(fixture.expected.valid);
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
      if (fixture.expected.response) {
        const responseCode = isObject(fixture.expected.response)
          ? fixture.expected.response['code']
          : undefined;
        if (fixture.expected.errorCode || typeof responseCode === 'string') {
          expect(isSynchronizationError(fixture.expected.response)).toBe(true);
          expect((fixture.expected.response as { code: string }).code).toBe(
            fixture.expected.errorCode ?? responseCode,
          );
        } else {
          expect(isOperationResult(fixture.expected.response), fixture.id).toBe(true);
        }
      }
      if (fixture.response) {
        expect(isChangePage(fixture.response)).toBe(true);
      }
    }
  });

  it('keeps declarative server state and expected protocol responses well formed', () => {
    for (const fixture of loadFixtures()) {
      if (fixture.state) {
        expect(Array.isArray(fixture.state.records)).toBe(true);
        for (const record of fixture.state.records) {
          expect(isTechnicalRecord(record)).toBe(true);
        }
      }
      if (fixture.expected.response) {
        expect(typeof fixture.expected.response).toBe('object');
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

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (isObject(value)) {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}
