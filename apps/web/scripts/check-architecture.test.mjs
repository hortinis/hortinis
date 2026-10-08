import assert from 'node:assert/strict';
import test from 'node:test';
import { ESLint } from 'eslint';
import { fileURLToPath } from 'node:url';
import { checkArchitecture } from './check-architecture.mjs';

test('the application passes its web architecture checks', async () => {
  const result = await checkArchitecture('src/app');
  assert.equal(
    result.lintResults.reduce((count, lintResult) => count + lintResult.errorCount, 0),
    0,
  );
  assert.deepEqual(result.cycles, []);
});

test('a component importing persistence directly fails', async () => {
  const result = await checkArchitecture('scripts/architecture-fixtures/component-persistence');
  assert.ok(
    result.lintResults.some((lintResult) =>
      lintResult.messages.some((message) => message.messageId === 'forbidden'),
    ),
  );
});

test('a pure rule importing Angular fails', async () => {
  const result = await checkArchitecture('scripts/architecture-fixtures/rule-framework');
  assert.ok(
    result.lintResults.some((lintResult) =>
      lintResult.messages.some((message) =>
        message.message.includes('Pure rules must not depend on frameworks'),
      ),
    ),
  );
});

test('a pure rule importing an external package fails', async () => {
  const result = await checkArchitecture('scripts/architecture-fixtures/rule-vendor');
  assert.ok(
    result.lintResults.some((lintResult) =>
      lintResult.messages.some((message) => message.messageId === 'forbidden'),
    ),
  );
});

test('an internal import cycle fails', async () => {
  const result = await checkArchitecture('scripts/architecture-fixtures/import-cycle');
  assert.notDeepEqual(result.cycles, []);
});

test('a synchronization rule importing HTTP fails', async () => {
  const result = await checkArchitecture('scripts/architecture-fixtures/sync/http-leak');
  assert.ok(
    result.lintResults.some((lintResult) =>
      lintResult.messages.some((message) => message.messageId === 'forbidden'),
    ),
  );
});

const eslint = new ESLint({ cwd: fileURLToPath(new URL('..', import.meta.url)) });

test('synchronization and persistence production files enforce size and complexity limits', async () => {
  const oversized = 'export const values = [\n' + '1,\n'.repeat(351) + '];';
  const complex =
    'export function choose(value: number) {\n' +
    Array.from({ length: 16 }, (_, index) => `if (value === ${index}) return ${index};`).join(
      '\n',
    ) +
    '\nreturn -1;\n}';
  for (const directory of ['sync', 'persistence']) {
    const [size] = await eslint.lintText(oversized, {
      filePath: `src/app/${directory}/oversized.ts`,
    });
    assert.ok(size.messages.some((message) => message.ruleId === 'max-lines'));
    const [complexity] = await eslint.lintText(complex, {
      filePath: `src/app/${directory}/complex.ts`,
    });
    assert.ok(complexity.messages.some((message) => message.ruleId === 'complexity'));
    const [spec] = await eslint.lintText(oversized + '\n' + complex, {
      filePath: `src/app/${directory}/large.spec.ts`,
    });
    assert.ok(
      spec.messages.every((message) => !['max-lines', 'complexity'].includes(message.ruleId)),
    );
  }
});

test('the synchronization facade has a tighter size limit', async () => {
  const [result] = await eslint.lintText('export const values = [\n' + '1,\n'.repeat(151) + '];', {
    filePath: 'src/app/sync/technical-record-synchronization-service.ts',
  });
  assert.ok(result.messages.some((message) => message.ruleId === 'max-lines'));
});
