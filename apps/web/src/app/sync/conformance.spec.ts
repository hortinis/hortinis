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

interface CapabilityManifest {
  fixtures: {
    file: string;
    status: 'implemented' | 'planned';
    suite?: 'value' | 'access';
    consumers: string[];
  }[];
}

const fixtureDirectory = join(__dirname, '../../../../../contracts/sync/fixtures');
const capabilityManifest = JSON.parse(
  readFileSync(join(fixtureDirectory, 'capabilities.json'), 'utf8'),
) as CapabilityManifest;
const fixtureNames = capabilityManifest.fixtures
  .filter(({ consumers, suite }) => consumers.includes('typescript') && suite === undefined)
  .map(({ file }) => file);

describe('technical synchronization conformance fixtures', () => {
  it('consumes the shared deletion result, change, and retired-identifier fixtures', () => {
    const fixtureValue = (name: string): unknown =>
      (JSON.parse(readFileSync(join(fixtureDirectory, name), 'utf8')) as { value: unknown }).value;
    const accepted = fixtureValue('delete-accepted.json');
    const page = fixtureValue('tombstone-change-page.json');
    const retired = fixtureValue('record-identifier-retired.json');
    expect(isOperationResult(accepted)).toBe(true);
    expect(isChangePage(page)).toBe(true);
    expect(isSynchronizationError(retired)).toBe(true);
    const deletion = {
      kind: 'delete',
      operationId: '01890f3e-7c5a-7b16-8abc-0123456789ab',
      recordId: '01890f3e-7c5a-7b15-8abc-0123456789ab',
      expectedRevision: '2',
    };
    expect(isTechnicalRecordOperation(deletion)).toBe(true);
    expect(isTechnicalRecordOperation({ ...deletion, value: 'unexpected' })).toBe(false);
    expect(isTechnicalRecordOperation({ ...deletion, expectedRevision: '0' })).toBe(false);
    expect(
      isOperationResult({
        ...(accepted as object),
        record: {
          recordId: '01890f3e-7c5a-7b15-8abc-0123456789ab',
          revision: '3',
          value: 'invalid',
        },
      }),
    ).toBe(false);
  });

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
