import assert from 'node:assert/strict';
import test from 'node:test';
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
