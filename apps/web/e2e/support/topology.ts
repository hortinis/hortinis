import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';

const execute = promisify(execFile);

export function topologyProjectName(): string {
  const project = process.env['HORTINIS_TOPOLOGY_PROJECT_NAME'];
  if (!project || !/^hortinis-sync-test-\d+-[a-f0-9]{16}$/.test(project)) {
    throw new Error('Run this suite through pnpm test:e2e:topology.');
  }
  return project;
}

export async function topologyCommand(
  command: 'stop' | 'start' | 'restart',
  service: 'postgres' | 'sync',
): Promise<void> {
  await execute('docker', [
    'compose',
    '--project-name',
    topologyProjectName(),
    '--file',
    resolve(__dirname, '../../../../infrastructure/docker/compose.yaml'),
    '--file',
    resolve(__dirname, '../../../../infrastructure/docker/compose.sync-test.yaml'),
    command,
    service,
  ]);
}

export async function synchronizationDiagnostics(): Promise<string> {
  const result = await execute('docker', ['logs', `${topologyProjectName()}-sync-1`], {
    maxBuffer: 4 * 1024 * 1024,
  });
  return `${result.stdout}\n${result.stderr}`;
}
