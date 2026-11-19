import { recordKinds } from './catalog-record-kinds.mjs';
import catalogContract from './catalog-validators.mjs';
import {
  CatalogEntry,
  CatalogImportError,
  CatalogRecord,
  CatalogVersion,
  object,
} from './catalog-record';

export interface CatalogFile {
  readonly name: string;
  readonly size: number;
  arrayBuffer(): Promise<ArrayBuffer>;
}

interface Artifact {
  path: string;
  byteSize: number;
  sha256: string;
}

interface Chunk extends Artifact {
  kind: 'jsonl-gzip';
  schemaId: string;
  entryCount: number;
}

interface Metadata extends Artifact {
  kind: 'schema' | 'json-document';
  role: string;
  schemaId?: string;
  mediaType: string;
}

interface Manifest extends CatalogVersion {
  minimumConsumerVersion: string;
  requiredArtifacts: (Chunk | Metadata)[];
  optionalArtifacts: (Chunk | Metadata)[];
}

interface SelectedArtifact {
  artifact: Chunk | Metadata;
  file: CatalogFile;
}

const schemaPrefix = 'urn:hortinis:plants:schema:v1:';
const chunkSchemas = new Map<string, CatalogEntry['kind']>(
  recordKinds.map((kind) => [schemaPrefix + kind, kind]),
);
const maxManifestBytes = 256 * 1024;
const maxChunkBytes = 2 * 1024 * 1024;
const maxMetadataBytes = 8 * 1024 * 1024;
const maxLineBytes = 16 * 1024 * 1024;
const maxInflatedChunkBytes = 64 * 1024 * 1024;
const maxInflatedReleaseBytes = 128 * 1024 * 1024;
const maxArtifacts = 256;

export async function verifyCatalogFiles(files: readonly CatalogFile[], signal: AbortSignal) {
  const byName = new Map<string, CatalogFile>();
  for (const file of files) {
    if (byName.has(file.name)) {
      throw new CatalogImportError('The selected file set contains a duplicate filename.');
    }
    byName.set(file.name, file);
  }

  const manifestFile = byName.get('manifest.json');
  if (!manifestFile) {
    throw new CatalogImportError('Select manifest.json and the artifacts it references.');
  }
  if (manifestFile.size > maxManifestBytes) {
    throw new CatalogImportError('The catalog manifest exceeds the preview size limit.');
  }

  const manifestData = parseJson(await manifestFile.arrayBuffer(), 'manifest.json');
  validate(schemaPrefix + 'release-manifest', manifestData, 'manifest.json');
  const manifest = manifestData as Manifest;
  if (manifest.schemaVersion !== '1.0.0' || manifest.profile !== 'dev-validation') {
    throw new CatalogImportError(
      'This preview supports schema 1.0.0 and the dev-validation profile.',
    );
  }
  if (!supportsMinimum(manifest.minimumConsumerVersion)) {
    throw new CatalogImportError('This release requires an unsupported consumer version.');
  }

  const descriptors = [...manifest.requiredArtifacts, ...manifest.optionalArtifacts];
  if (descriptors.length > maxArtifacts) {
    throw new CatalogImportError('The catalog has too many artifacts for this preview.');
  }

  const paths = new Set(['manifest.json']);
  const selected: SelectedArtifact[] = [];
  for (const artifact of descriptors) {
    signal.throwIfAborted();
    if (
      !/^[A-Za-z0-9]/.test(artifact.path) ||
      /[^A-Za-z0-9._-]/.test(artifact.path) ||
      paths.has(artifact.path)
    ) {
      throw new CatalogImportError(
        'The manifest contains an unsupported or duplicate artifact path.',
      );
    }
    paths.add(artifact.path);
    const file = byName.get(artifact.path);
    if (!file && manifest.optionalArtifacts.includes(artifact)) {
      continue;
    }
    if (!file) {
      throw new CatalogImportError(`Missing required catalog file: ${artifact.path}.`);
    }
    if (artifact.kind === 'jsonl-gzip' && !chunkSchemas.has(artifact.schemaId)) {
      throw new CatalogImportError(`Unsupported record schema in ${artifact.path}.`);
    }
    const selection = { artifact, file };
    await readVerifiedArtifact(selection, signal);
    selected.push(selection);
  }
  signal.throwIfAborted();

  const schema = manifest.requiredArtifacts.find((artifact) => artifact.path === 'schemas.json');
  if (
    schema?.kind !== 'schema' ||
    schema.role !== 'schemas' ||
    schema.mediaType !== 'application/json' ||
    schema.schemaId !== schemaPrefix + 'audit-bundle'
  ) {
    throw new CatalogImportError(
      'The catalog schema bundle does not match the pinned preview contract.',
    );
  }
  const provenance = manifest.requiredArtifacts.find(
    (artifact) => artifact.path === 'source-manifests.json',
  );
  if (provenance?.kind !== 'json-document' || provenance.mediaType !== 'application/json') {
    throw new CatalogImportError('The preview requires source-manifests.json.');
  }

  const schemaBytes = await readVerifiedArtifact(
    { artifact: schema, file: byName.get(schema.path)! },
    signal,
  );
  await verifySchemas(parseJson(schemaBytes, schema.path), signal);
  return { manifest, selected };
}

export async function* catalogEntries(
  files: readonly SelectedArtifact[],
  signal: AbortSignal,
): AsyncGenerator<{ entry: CatalogEntry; bytes: number }> {
  let releaseBytes = 0;
  for (const selection of files) {
    signal.throwIfAborted();
    const { artifact } = selection;
    if (artifact.path === 'schemas.json') {
      continue;
    }
    const artifactBytes = await readVerifiedArtifact(selection, signal);
    if (artifact.kind !== 'jsonl-gzip') {
      const data = parseJson(artifactBytes, artifact.path);
      if (artifact.path === 'source-manifests.json') {
        if (!Array.isArray(data)) {
          throw new CatalogImportError(
            'source-manifests.json must contain a list of source manifests.',
          );
        }
        const averageBytes = Math.ceil(artifactBytes.byteLength / Math.max(1, data.length));
        for (const value of data as unknown[]) {
          signal.throwIfAborted();
          validate(schemaPrefix + 'source-manifest', value, artifact.path);
          yield {
            entry: { kind: 'source-manifest', record: value as CatalogRecord },
            bytes: averageBytes,
          };
        }
      } else if (
        artifact.kind !== 'json-document' ||
        artifact.role !== 'other' ||
        artifact.mediaType !== 'application/json' ||
        artifact.schemaId !== undefined
      ) {
        throw new CatalogImportError(`Unsupported metadata artifact: ${artifact.path}.`);
      }
      continue;
    }

    const kind = chunkSchemas.get(artifact.schemaId)!;
    let count = 0;
    for await (const bytes of jsonLines(artifactBytes, artifact.path, signal)) {
      releaseBytes += bytes.byteLength + 1;
      count++;
      if (count > artifact.entryCount || releaseBytes > maxInflatedReleaseBytes) {
        throw new CatalogImportError(`Count or inflated size check failed for ${artifact.path}.`);
      }
      const location = `${artifact.path}, line ${count}`;
      const value = parseJson(bytes, location);
      validate(artifact.schemaId, value, location);
      const record = value as CatalogRecord;
      const subject = object(record['subject']);
      const subjectId = typeof subject['id'] === 'string' ? subject['id'] : undefined;
      yield { entry: { kind, record, subjectId }, bytes: bytes.byteLength };
    }
    if (count !== artifact.entryCount) {
      throw new CatalogImportError(`Record count check failed for ${artifact.path}.`);
    }
  }
}

// Preflight verifies the whole set. On reread, verify the in-memory bytes that
// will actually be parsed, since the selected file can change on disk.
async function readVerifiedArtifact(
  { artifact, file }: SelectedArtifact,
  signal: AbortSignal,
): Promise<ArrayBuffer> {
  signal.throwIfAborted();
  const maxBytes = artifact.kind === 'jsonl-gzip' ? maxChunkBytes : maxMetadataBytes;
  if (file.size !== artifact.byteSize || file.size > maxBytes) {
    throw new CatalogImportError(`Size check failed for ${artifact.path}.`);
  }
  const bytes = await file.arrayBuffer();
  signal.throwIfAborted();
  if (bytes.byteLength !== artifact.byteSize) {
    throw new CatalogImportError(`Size check failed for ${artifact.path}.`);
  }
  const digest = await sha256(bytes);
  signal.throwIfAborted();
  if (digest !== artifact.sha256) {
    throw new CatalogImportError(`SHA-256 check failed for ${artifact.path}.`);
  }
  return bytes;
}

async function* jsonLines(bytes: ArrayBuffer, path: string, signal: AbortSignal) {
  const compressed = new Uint8Array(bytes);
  const blockBytes = 64 * 1024;
  let position = 0;
  // Feed the checked bytes in bounded blocks so decompression retains backpressure.
  const stream = new ReadableStream<Uint8Array<ArrayBuffer>>({
    pull(controller) {
      const block = compressed.subarray(position, position + blockBytes);
      controller.enqueue(block);
      position += block.byteLength;
      if (position >= compressed.byteLength) {
        controller.close();
      }
    },
  });
  const reader = stream.pipeThrough(new DecompressionStream('gzip'), { signal }).getReader();
  let parts: Uint8Array<ArrayBuffer>[] = [];
  let length = 0;
  let total = 0;

  function append(part: Uint8Array<ArrayBuffer>) {
    if (part.length) {
      length += part.length;
      if (length > maxLineBytes) {
        throw new CatalogImportError(`A record exceeds the preview line limit in ${path}.`);
      }
      parts.push(part);
    }
  }

  try {
    for (;;) {
      signal.throwIfAborted();
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      total += value.length;
      if (total > maxInflatedChunkBytes) {
        throw new CatalogImportError(`Inflated chunk exceeds the preview limit in ${path}.`);
      }
      let start = 0;
      for (let end = value.indexOf(10); end >= 0; end = value.indexOf(10, start)) {
        append(value.subarray(start, end));
        if (!length) {
          throw new CatalogImportError(`Blank JSONL record in ${path}.`);
        }
        const line = new Uint8Array(length);
        let offset = 0;
        for (const part of parts) {
          line.set(part, offset);
          offset += part.length;
        }
        if (line.includes(13)) {
          throw new CatalogImportError(`Invalid JSONL line ending in ${path}.`);
        }
        parts = [];
        length = 0;
        start = end + 1;
        yield line;
      }
      append(value.subarray(start));
    }
    if (length) {
      throw new CatalogImportError(`Missing final newline in ${path}.`);
    }
  } catch (error) {
    signal.throwIfAborted();
    if (error instanceof CatalogImportError) {
      throw error;
    }
    throw new CatalogImportError(`Could not decompress ${path}.`);
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function parseJson(bytes: ArrayBuffer | Uint8Array<ArrayBuffer>, location: string): unknown {
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    const parsed = JSON.parse(text, (_key, value: unknown) => {
      if (typeof value === 'number' && !Number.isFinite(value)) {
        throw new Error('Non-finite JSON number.');
      }
      return value;
    }) as unknown;
    rejectDuplicateKeys(text);
    return parsed;
  } catch {
    throw new CatalogImportError(`Invalid UTF-8 or JSON in ${location}.`);
  }
}

// Native JSON parsing checks the syntax first. Only quoted tokens followed by
// a colon are object keys, so the scan can skip all scalar values.
function rejectDuplicateKeys(text: string): void {
  const objects: Set<string>[] = [];
  for (const [token, quoted, colon] of text.matchAll(/("(?:[^"\\]|\\.)*")\s*(:)?|[{}]/g)) {
    if (token === '{') {
      objects.push(new Set());
    } else if (token === '}') {
      objects.pop();
    } else if (colon) {
      const key = JSON.parse(quoted) as string;
      const keys = objects.at(-1)!;
      if (keys.has(key)) {
        throw new Error('Duplicate JSON object key.');
      }
      keys.add(key);
    }
  }
}

function validate(schemaId: string, value: unknown, location: string): void {
  if (!catalogContract.validators[schemaId]?.(value)) {
    throw new CatalogImportError(`Schema validation failed in ${location}.`);
  }
}

function supportsMinimum(minimum: string): boolean {
  // The preview supports stable consumer versions only. Build metadata does not affect precedence.
  const stableVersion = /^(\d+)\.(\d+)\.(\d+)(?:\+[0-9A-Za-z.-]+)?$/;
  const required = stableVersion.exec(minimum);
  const current = stableVersion.exec(catalogContract.consumerVersion);
  if (
    !required ||
    !current ||
    required[0] !== minimum ||
    current[0] !== catalogContract.consumerVersion
  ) {
    return false;
  }
  for (const part of [1, 2, 3]) {
    const left = BigInt(required[part]);
    const right = BigInt(current[part]);
    if (left !== right) {
      return left < right;
    }
  }
  return true;
}

async function verifySchemas(bundleData: unknown, signal: AbortSignal): Promise<void> {
  const bundle = object(bundleData);
  if (
    bundle['$id'] !== schemaPrefix + 'audit-bundle' ||
    bundle['$schema'] !== 'https://json-schema.org/draft/2020-12/schema'
  ) {
    throw new CatalogImportError(
      'The catalog schema bundle does not match the pinned preview contract.',
    );
  }
  const definitions = object(bundle['$defs']);
  for (const [id, expected] of Object.entries(catalogContract.fingerprints)) {
    signal.throwIfAborted();
    const serialized = JSON.stringify(definitions[id], (_key, value: unknown) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        return value;
      }
      const properties = object(value);
      return Object.fromEntries(
        Object.keys(properties)
          .sort()
          .map((key) => [key, properties[key]]),
      );
    });
    if (
      serialized === undefined ||
      (await sha256(new TextEncoder().encode(serialized))) !== expected
    ) {
      throw new CatalogImportError(
        'The catalog contains a schema that differs from the pinned preview contract.',
      );
    }
  }
}

async function sha256(bytes: BufferSource): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}
