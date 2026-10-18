import { readdir, readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const root = new URL('../dist/web/browser/', import.meta.url);
const retired = [
  '/login/password',
  '/login/passkey',
  '/setup/passkey',
  '/step-up/',
  '/credentials/options',
  '/sessions/revoke',
  '/recovery-codes',
  '/recovery/operator',
  '/recovery/passkey',
  'navigator.credentials',
];
for (const file of await readdir(root)) {
  if (!file.endsWith('.js')) continue;
  const source = await readFile(new URL(file, root), 'utf8');
  for (const value of retired)
    assert(!source.includes(value), 'Retired authentication in frontend bundle');
}
console.log('Frontend authentication replacement isolation passed.');
