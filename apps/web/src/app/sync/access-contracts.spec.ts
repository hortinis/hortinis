import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { isAccessContractSchema, parseAccessContract } from './access-contracts.rule';
import type {
  LocalDataSetId,
  ServerInstanceId,
  SynchronizationScopeId,
} from './access-contracts.rule';

interface AccessFixture {
  groups: {
    schema: string;
    cases: { id: string; valid: boolean; value: unknown }[];
  }[];
}

interface Manifest {
  fixtures: { file: string; capability: string; suite?: string; consumers: string[] }[];
}

const directory = join(__dirname, '../../../../../contracts/sync/fixtures');

describe('S2 access contract parsing', () => {
  it('consumes every declared access fixture without unsupported dispatch or skipped cases', () => {
    const manifest = JSON.parse(
      readFileSync(join(directory, 'capabilities.json'), 'utf8'),
    ) as Manifest;
    const declarations = manifest.fixtures.filter(({ suite }) => suite === 'access');
    expect(declarations.length).toBeGreaterThan(0);
    const covered = new Set<string>();
    for (const declaration of declarations) {
      expect(declaration.capability).toBe('access-contract-parsing');
      expect(declaration.consumers).toContain('typescript');
      const fixture = JSON.parse(
        readFileSync(join(directory, declaration.file), 'utf8'),
      ) as AccessFixture;
      expect(fixture.groups.length).toBeGreaterThan(0);
      for (const group of fixture.groups) {
        const name = group.schema.replace(/\.json$/, '');
        if (!isAccessContractSchema(name)) throw new Error('Unsupported access fixture schema.');
        expect(covered.has(name)).toBe(false);
        covered.add(name);
        expect(group.cases.length).toBeGreaterThan(0);
        for (const example of group.cases) {
          if (example.valid) {
            expect(
              parseAccessContract(name, example.value),
              `${declaration.file}: ${name}: ${example.id}`,
            ).toEqual(example.value);
          } else {
            expect(
              () => parseAccessContract(name, example.value),
              `${declaration.file}: ${name}: ${example.id}`,
            ).toThrow('The access contract is invalid.');
          }
        }
      }
    }
    expect(covered.size).toBe(18);
  });

  it('keeps identity brands distinct at compile time', () => {
    const value = '01890f3e-7c5a-7b12-8abc-0123456789ab';
    const local: LocalDataSetId = parseAccessContract('LocalDataSetId', value);
    const instance: ServerInstanceId = parseAccessContract('ServerInstanceId', value);
    const scope: SynchronizationScopeId = parseAccessContract('SynchronizationScopeId', value);
    // @ts-expect-error Local identity cannot be substituted for server scope identity.
    const wrongScope: SynchronizationScopeId = local;
    // @ts-expect-error Server instance identity cannot be substituted for local identity.
    const wrongLocal: LocalDataSetId = instance;
    expect([scope, wrongScope, wrongLocal]).toEqual([value, value, value]);
  });

  it('rejects inherited validator names', () => {
    expect(isAccessContractSchema('toString')).toBe(false);
  });
});
