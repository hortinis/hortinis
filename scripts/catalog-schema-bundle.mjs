import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const pinnedDigest = '8291e55c9e94e17292df54367a97377f199210319016ed37b98fc32e4366be2e';

export function readCatalogSchemaBundle() {
  const bundlePath = process.env['HORTINIS_CATALOG_SCHEMA_BUNDLE'];
  if (!bundlePath?.trim()) {
    throw new Error(
      "Set HORTINIS_CATALOG_SCHEMA_BUNDLE to the path of the reviewed rehearsal's schemas.json.",
    );
  }
  const path = resolve(bundlePath);
  let bytes;
  try {
    bytes = readFileSync(path);
  } catch (cause) {
    throw new Error(
      `Cannot read the catalog schema bundle at ${path}. Check the path in HORTINIS_CATALOG_SCHEMA_BUNDLE.`,
      { cause },
    );
  }

  const digest = createHash('sha256').update(bytes).digest('hex');
  if (digest !== pinnedDigest) {
    throw new Error(
      `Catalog schema bundle SHA-256 mismatch at ${path}. Expected ${pinnedDigest}, received ${digest}.`,
    );
  }

  const bundle = JSON.parse(bytes.toString('utf8'));
  if (
    bundle.$schema !== 'https://json-schema.org/draft/2020-12/schema' ||
    bundle.$id !== 'urn:hortinis:plants:schema:v1:audit-bundle' ||
    !bundle.$defs ||
    typeof bundle.$defs !== 'object' ||
    Array.isArray(bundle.$defs)
  ) {
    throw new Error('The catalog schema bundle has an unexpected format.');
  }
  return bundle;
}
