import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import standalone from 'ajv/dist/standalone/index.js';
import { build } from 'esbuild';
import { recordKinds } from '../src/app/features/catalog-preview/catalog-record-kinds.mts';
import { readCatalogSchemaBundle } from './catalog-schema-bundle.mjs';

const feature = new URL('../src/app/features/catalog-preview/', import.meta.url);
const registry = new Ajv2020({ strict: false, ownProperties: true, code: { source: true } });
const { $defs: definitions } = readCatalogSchemaBundle();
const schemaPrefix = 'urn:hortinis:plants:schema:v1:';
const ids = [...recordKinds, 'release-manifest', 'source-manifest'].map(
  (name) => schemaPrefix + name,
);
const exportsByName = Object.fromEntries(ids.map((id, index) => [`schema${index}`, id]));
const schemas = new Map();

for (const id of ids) {
  addSchema(id);
}
const fingerprints = Object.fromEntries(
  [...schemas].map(([id, schema]) => [
    id,
    createHash('sha256').update(canonicalSchema(schema)).digest('hex'),
  ]),
);

function addSchema(id) {
  if (schemas.has(id)) {
    return;
  }
  const schema = definitions[id];
  if (schema?.$id !== id) {
    throw new Error(`The pinned catalog bundle is missing schema ${id}.`);
  }
  schemas.set(id, schema);
  registry.addSchema(schema);
  for (const reference of externalReferences(schema)) {
    addSchema(reference);
  }
}

function* externalReferences(value) {
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      if (key === '$ref' && typeof child === 'string' && !child.startsWith('#')) {
        yield child.split('#', 1)[0];
      } else {
        yield* externalReferences(child);
      }
    }
  }
}

const { version } = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
const validators = ids.map((id, index) => `${JSON.stringify(id)}: exports.schema${index}`);
const contents = [
  standalone(registry, exportsByName),
  `module.exports = { consumerVersion: ${JSON.stringify(version)}, fingerprints: ${JSON.stringify(fingerprints)}, validators: { ${validators.join(',')} } };`,
].join('\n');

await build({
  stdin: { contents, resolveDir: fileURLToPath(new URL('../', import.meta.url)) },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  outfile: fileURLToPath(new URL('catalog-validators.mjs', feature)),
});

function canonicalSchema(schema) {
  return JSON.stringify(schema, (_key, value) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return value;
    }
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, value[key]]),
    );
  });
}
