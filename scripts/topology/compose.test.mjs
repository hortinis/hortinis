import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const initializer = join(root, 'infrastructure/docker/initialize-gradle-cache.sh');

function composeConfig(uid = '', gid = '', integrated = false) {
  const args = ['compose', '--file', 'infrastructure/docker/compose.yaml'];
  if (integrated) args.push('--file', 'infrastructure/docker/compose.sync-test.yaml');
  args.push('config', '--format', 'json');
  const result = spawnSync('docker', args, {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...process.env,
      HORTINIS_UID: uid,
      HORTINIS_GID: gid,
      HORTINIS_POSTGRES_PASSWORD: 'compose-config-test-only',
    },
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

for (const integrated of [false, true]) {
  test(`configures cache ownership and sync identity in ${integrated ? 'integrated' : 'development'} Compose`, () => {
    for (const [uid, gid, identity] of [
      ['', '', '0:0'],
      ['1001', '1002', '1001:1002'],
    ]) {
      const { services } = composeConfig(uid, gid, integrated);
      assert.equal(services.sync.user, identity);
      assert.equal(services.sync.environment.GRADLE_USER_HOME, '/home/gradle/.gradle');
      assert.equal(
        services.sync.depends_on['gradle-cache-init'].condition,
        'service_completed_successfully',
      );
      assert.equal(services.sync.depends_on.postgres.condition, 'service_healthy');
      assert.equal(services.postgres.user, undefined);
      const init = services['gradle-cache-init'];
      assert.equal(init.user, '0:0');
      assert.deepEqual(init.command, identity.split(':'));
      assert.equal(init.volumes.length, 2);
      assert.equal(init.volumes.find((volume) => volume.type === 'volume').source, 'gradle-cache');
      const script = init.volumes.find((volume) => volume.type === 'bind');
      assert.equal(script.source, initializer);
      assert.equal(script.read_only, true);
      assert.equal(
        services.sync.volumes.find((volume) => volume.type === 'volume').source,
        'gradle-cache',
      );
      if (integrated)
        assert.ok(services.sync.command.includes('/tmp/hortinis-topology-project-cache'));
    }
  });
}

test('cache initialization rejects nonnumeric IDs and limits ownership changes to the cache', () => {
  const directory = mkdtempSync(join(tmpdir(), 'hortinis-cache-init-'));
  const log = join(directory, 'arguments.json');
  try {
    writeFileSync(
      join(directory, 'chown'),
      `#!/usr/bin/env node
require('node:fs').writeFileSync(process.env.TEST_CHOWN_LOG, JSON.stringify(process.argv.slice(2)));
`,
      { mode: 0o755 },
    );
    const env = {
      ...process.env,
      PATH: `${directory}${delimiter}${process.env.PATH}`,
      TEST_CHOWN_LOG: log,
    };
    for (const args of [
      [],
      ['1001'],
      ['1001', 'invalid'],
      ['-1', '1002'],
      ['1001', '1002', 'extra'],
    ]) {
      const result = spawnSync('bash', [initializer, ...args], { env, encoding: 'utf8' });
      assert.equal(result.status, 1, result.stderr);
      assert.match(result.stderr, /must be numeric container IDs/);
      assert.throws(() => readFileSync(log));
    }
    const result = spawnSync('bash', [initializer, '1001', '1002'], { env, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(readFileSync(log, 'utf8')), [
      '-hR',
      '--',
      '1001:1002',
      '/home/gradle/.gradle',
    ]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
