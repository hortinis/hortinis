import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const runner = fileURLToPath(new URL('../validate-sync-topology.sh', import.meta.url));

function runTopology(failAt = '', failureCode = 0) {
  const directory = mkdtempSync(join(tmpdir(), 'hortinis-topology-runner-'));
  const log = join(directory, 'calls.jsonl');
  const stub = `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const tool = path.basename(process.argv[1]);
const args = process.argv.slice(2);
fs.appendFileSync(process.env.TEST_CALL_LOG, JSON.stringify({
  tool, args,
  project: process.env.HORTINIS_TOPOLOGY_PROJECT_NAME,
  passwordIsGenerated: /^[a-f0-9]{64}$/.test(process.env.HORTINIS_POSTGRES_PASSWORD),
}) + '\\n');
const stage = tool === 'pnpm' ? 'test' : args.includes('up') ? 'up' : 'down';
process.exit(stage === process.env.TEST_FAIL_AT ? Number(process.env.TEST_FAILURE_CODE) : 0);
`;
  try {
    for (const tool of ['docker', 'pnpm']) {
      writeFileSync(join(directory, tool), stub, { mode: 0o755 });
    }
    const result = spawnSync('bash', [runner], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${directory}${delimiter}${process.env.PATH}`,
        TEST_CALL_LOG: log,
        TEST_FAIL_AT: failAt,
        TEST_FAILURE_CODE: String(failureCode),
        HORTINIS_TOPOLOGY_PROJECT_NAME: 'existing-developer-project',
        HORTINIS_POSTGRES_PASSWORD: 'existing-developer-password',
      },
    });
    assert.ifError(result.error);
    const calls = readFileSync(log, 'utf8').trim().split('\n').map(JSON.parse);
    return { result, calls };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function assertIsolatedCleanup(calls) {
  const project = calls[0].project;
  assert.match(project, /^hortinis-sync-test-\d+-[a-f0-9]{16}$/);
  for (const call of calls) {
    assert.equal(call.project, project);
    assert.equal(call.passwordIsGenerated, true);
    if (call.tool === 'docker') {
      assert.equal(call.args[call.args.indexOf('--project-name') + 1], project);
    }
  }
  const cleanup = calls.at(-1);
  assert.equal(cleanup.tool, 'docker');
  assert.deepEqual(cleanup.args.slice(-3), ['down', '--volumes', '--remove-orphans']);
}

test('runs integration tests in a fresh project and removes only its volumes', () => {
  const first = runTopology();
  const second = runTopology();
  for (const { result, calls } of [first, second]) {
    assert.equal(result.status, 0, result.stderr);
    assertIsolatedCleanup(calls);
    assert.deepEqual(calls[0].args.slice(-2), ['up', '--wait']);
    assert.deepEqual(calls[1].args, ['--filter', '@hortinis/web', 'test:e2e:integration']);
  }
  assert.notEqual(first.calls[0].project, second.calls[0].project);
});

for (const [stage, code, count] of [['up', 17, 2], ['test', 23, 3], ['down', 29, 3]]) {
  test(`reports ${stage} failure and still attempts cleanup`, () => {
    const { result, calls } = runTopology(stage, code);
    assert.equal(result.status, code, result.stderr);
    assert.equal(calls.length, count);
    assertIsolatedCleanup(calls);
  });
}
