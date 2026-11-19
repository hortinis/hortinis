import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { readCatalogSchemaBundle } from '../scripts/catalog-schema-bundle.mjs';

const prefix = 'urn:hortinis:plants:schema:v1:';

export interface FixtureOptions {
  version?: string;
  schemaVersion?: string;
  minimumConsumerVersion?: string;
  profile?: string;
  invalidRecord?: boolean;
  brokenReference?: boolean;
  wrongReferenceType?: boolean;
  duplicateId?: boolean;
  schemaDrift?: boolean;
  badCount?: boolean;
  path?: string;
}

export function previewFixture(options: FixtureOptions = {}): Map<string, Buffer> {
  const { $schema, $id, $defs: schemas } = readCatalogSchemaBundle();
  if (options.schemaDrift) {
    schemas[prefix + 'source'].title = 'Different source contract';
  }

  const common = { status: 'active', evidenceReferenceIds: ['evidence'] };
  const subject = { type: 'plant-concept', id: 'plant' };
  const fact = {
    ...common,
    id: 'fact',
    subject,
    predicate: 'frost_sensitivity',
    value: 'sensitive',
    contextId: options.brokenReference
      ? 'missing'
      : options.wrongReferenceType
        ? 'source'
        : 'context',
    reviewId: 'content_review',
  };
  const chunks = {
    'plant-concept': [
      { ...common, id: 'plant', kind: 'generic' },
      { ...common, id: 'growing_type', kind: 'growing-type', parentPlantConceptId: 'plant' },
    ],
    cultivar: [
      { ...common, id: 'cultivar', plantConceptId: 'plant', denomination: 'Fixture cultivar' },
    ],
    'localized-name': [
      {
        id: 'name',
        subject,
        languageTag: 'en',
        value: 'Fixture plant',
        kind: 'common',
        preferred: true,
        evidenceReferenceIds: ['evidence'],
      },
    ],
    'plant-fact': [
      options.invalidRecord ? { ...fact, value: 'invalid' } : fact,
      { ...fact, id: options.duplicateId ? 'fact' : 'alternative', value: 'tolerant' },
    ],
    'cultivation-rule': [
      {
        ...common,
        id: 'rule',
        subject: { type: 'cultivar', id: 'cultivar' },
        contextId: 'context',
        action: 'transplant',
        reviewId: 'content_review',
        timing: {
          type: 'relative-day-window',
          anchor: 'last_spring_frost',
          startOffsetDays: 14,
          endOffsetDays: 42,
        },
      },
    ],
    'cultivation-context': [
      {
        id: 'context',
        geographicScope: { type: 'unknown' },
        growingSystem: 'outdoor',
        propagation: 'transplant',
      },
    ],
    'evidence-reference': [
      {
        id: 'evidence',
        locator: 'fixture://source',
        sourceRecordKey: {
          source: {
            sourceId: 'source',
            sourceManifestId: 'source_manifest',
            sourceReleaseId: 'release',
          },
          recordId: 'fixture_record',
        },
        rights: {
          licenceId: 'licence',
          decision: 'pending-review',
          reason: 'Synthetic fixture',
          reviewId: 'rights_review',
        },
      },
    ],
    source: [{ id: 'source', name: 'Synthetic fixture source' }],
    licence: [
      {
        id: 'licence',
        name: 'Fixture licence',
        expression: 'UNLICENSED',
        referenceLocator: 'fixture://licence',
        commercialUse: 'unknown',
        attributionRequired: false,
      },
    ],
    review: [
      { id: 'rights_review', purpose: 'rights', status: 'unreviewed' },
      {
        id: 'content_review',
        purpose: 'content',
        status: 'accepted',
        reviewedAt: '2026-11-17T00:00:00Z',
        reviewerId: 'fixture_reviewer',
      },
    ],
  };
  const files = new Map<string, Buffer>();
  const integrity = (path: string, bytes: Buffer) => {
    files.set(path, bytes);
    return {
      path,
      byteSize: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
  };
  const chunkDescriptors = Object.entries(chunks).map(([kind, records]) => ({
    ...integrity(
      kind === 'plant-fact' && options.path ? options.path : kind + '.jsonl.gz',
      gzipSync(records.map((row) => JSON.stringify(row) + '\n').join('')),
    ),
    kind: 'jsonl-gzip',
    schemaId: prefix + kind,
    mediaType: 'application/x-ndjson',
    contentEncoding: 'gzip',
    entryCount: kind === 'plant-fact' && options.badCount ? records.length + 1 : records.length,
  }));
  const sourceManifests = [
    {
      id: 'source_manifest',
      provider: { id: 'fixture_provider', name: 'Fixture provider' },
      release: { identifier: 'release' },
      resources: [
        { locator: 'fixture://release', checksum: { algorithm: 'sha256', value: '0'.repeat(64) } },
      ],
      licenceReview: {
        status: 'unreviewed',
        declaredLicence: 'UNLICENSED',
        evidenceLocator: 'fixture://rights',
      },
      profileEligibility: [
        { profile: 'dev-validation', decision: 'pending-review', reason: 'Synthetic fixture' },
      ],
    },
  ];
  const metadata = [
    {
      ...integrity('schemas.json', Buffer.from(JSON.stringify({ $schema, $id, $defs: schemas }))),
      kind: 'schema',
      schemaId: prefix + 'audit-bundle',
      role: 'schemas',
      mediaType: 'application/json',
    },
    {
      ...integrity('source-manifests.json', Buffer.from(JSON.stringify(sourceManifests))),
      kind: 'json-document',
      role: 'other',
      mediaType: 'application/json',
    },
  ];
  files.set(
    'manifest.json',
    Buffer.from(
      JSON.stringify({
        catalogVersion: options.version ?? '1.0.0',
        schemaVersion: options.schemaVersion ?? '1.0.0',
        minimumConsumerVersion: options.minimumConsumerVersion ?? '0.0.0',
        generatedAt: '2026-11-17T00:00:00Z',
        profile: options.profile ?? 'dev-validation',
        requiredArtifacts: [...chunkDescriptors, ...metadata],
        optionalArtifacts: [],
      }),
    ),
  );
  return files;
}
