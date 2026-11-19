import { TestBed } from '@angular/core/testing';
import { previewFixture } from '../../../../test/catalog-preview-fixture';
import { CatalogPersistence } from '../../persistence/catalog/catalog-persistence';
import { HortinisDatabase } from '../../persistence/hortinis-database';
import { CatalogPreviewService } from './catalog-preview-service';
import type { CatalogFile } from './catalog-release';

describe('Catalog preview import', () => {
  let database: HortinisDatabase;
  let service: CatalogPreviewService;

  beforeEach(() => {
    database = new HortinisDatabase(`catalog-preview-${crypto.randomUUID()}`);
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    service = TestBed.inject(CatalogPreviewService);
  });

  afterEach(async () => {
    database.close();
    await database.delete();
  });

  it('keeps independent facts, subject scope and pending rights across reopening', async () => {
    await run(previewFixture());
    database.close();
    await database.open();

    const preview = (await service.browse())!;
    expect(preview.active.catalogVersion).toBe('1.0.0');
    expect(preview.subjects.map(({ kind }) => kind).sort()).toEqual([
      'cultivar',
      'generic',
      'growing-type',
    ]);

    const plant = await service.subject(preview.active.id, 'plant');
    expect(plant?.claims.map(({ id }) => id).sort()).toEqual(['alternative', 'fact']);
    expect(plant?.claims[0].provenance[0]).toMatchObject({
      decision: 'pending-review',
      sourceReleaseId: 'release',
      sourceManifestId: 'source_manifest',
    });
    expect(plant?.claims[0].geographyType).toBe('unknown');
    expect((await service.subject(preview.active.id, 'growing_type'))?.claims).toEqual([]);
    expect(
      (await service.subject(preview.active.id, 'cultivar'))?.claims.map(({ id }) => id),
    ).toEqual(['rule']);
  });

  it.each([
    ['invalid records', { invalidRecord: true }, /Schema validation failed.*plant-fact/],
    ['unsupported schemas', { schemaVersion: '1.1.0' }, /supports schema 1.0.0/],
    [
      'unsupported minimum versions',
      { minimumConsumerVersion: '1.0.0' },
      /unsupported consumer version/,
    ],
    ['commercial profiles', { profile: 'reviewed-catalog' }, /dev-validation profile/],
    ['schema drift', { schemaDrift: true }, /differs from the pinned/],
    ['broken references', { brokenReference: true }, /Broken cultivation-context reference/],
    [
      'references to the wrong family',
      { wrongReferenceType: true },
      /Broken cultivation-context reference/,
    ],
    ['duplicate IDs', { duplicateId: true }, /duplicate record ID/],
    ['bad counts', { badCount: true }, /Record count check failed/],
    ['nested paths', { path: 'nested/plant-fact.jsonl.gz' }, /artifact path/],
    ['unsafe paths', { path: '../plant-fact.jsonl.gz' }, /Schema validation failed|artifact path/],
    [
      'paths with a newline',
      { path: 'plant-fact.jsonl.gz\n' },
      /Schema validation failed|artifact path/,
    ],
  ])('rejects %s without changing the active snapshot', async (_label, options, message) => {
    await run(previewFixture());
    const original = await database.catalogState.get('preview');
    await expect(run(previewFixture({ ...options, version: '2.0.0' }))).rejects.toThrow(message);
    expect(await database.catalogState.get('preview')).toEqual(original);
  });

  it('checks all files and hashes before staging', async () => {
    const missing = previewFixture();
    missing.delete('plant-fact.jsonl.gz');
    await expect(run(missing)).rejects.toThrow(
      'Missing required catalog file: plant-fact.jsonl.gz',
    );
    const corrupt = previewFixture();
    const bytes = corrupt.get('plant-fact.jsonl.gz')!;
    bytes[bytes.length - 1] ^= 1;
    await expect(run(corrupt)).rejects.toThrow('SHA-256 check failed');
    await expect(database.catalogState.count()).resolves.toBe(0);
  });

  it.each([
    [
      'plant-fact.jsonl.gz',
      (bytes: Buffer) => {
        // Adjust the gzip header timestamp without changing the payload or size.
        const replacement = Buffer.from(bytes);
        replacement[4] ^= 1;
        return replacement;
      },
    ],
    [
      'source-manifests.json',
      (bytes: Buffer) =>
        Buffer.from(bytes.toString().replace('Fixture provider', 'Changed provider')),
    ],
    [
      'schemas.json',
      (bytes: Buffer) => {
        const { $schema, ...bundle } = JSON.parse(bytes.toString());
        return Buffer.from(JSON.stringify({ ...bundle, $schema }));
      },
    ],
  ])('rejects a changed %s before parsing its second read', async (name, replace) => {
    await run(previewFixture());
    await run(previewFixture({ version: '2.0.0' }));
    const before = await database.catalogState.get('preview');
    const originalCount = await database.catalogRecords.count();
    const fixture = previewFixture({ version: '3.0.0' });
    const original = fixture.get(name)!;
    const replacement = Uint8Array.from(replace(original));
    expect(replacement.byteLength).toBe(original.byteLength);
    expect(replacement).not.toEqual(Uint8Array.from(original));

    const read = vi
      .fn()
      .mockResolvedValue(replacement.buffer)
      .mockResolvedValueOnce(Uint8Array.from(original).buffer);
    const selected = selectFiles(fixture).map((file) =>
      file.name === name ? { ...file, arrayBuffer: read } : file,
    );

    await expect(service.import(selected, new AbortController().signal)).rejects.toThrow(
      `SHA-256 check failed for ${name}`,
    );
    expect(read).toHaveBeenCalledTimes(2);
    expect(await database.catalogState.get('preview')).toEqual(before);
    await expect(database.catalogRecords.count()).resolves.toBe(originalCount);
  });

  it('rejects duplicate keys in the selected manifest', async () => {
    const files = previewFixture();
    const manifest = files.get('manifest.json')!.toString();
    files.set('manifest.json', Buffer.from('{"schemaVersion":"1.1.0",' + manifest.slice(1)));
    await expect(run(files)).rejects.toThrow('Invalid UTF-8 or JSON in manifest.json');
    await expect(database.catalogState.count()).resolves.toBe(0);
  });

  it('preserves the active preview after cancellation with a staged batch', async () => {
    await run(previewFixture());
    const before = (await service.browse())!.active;
    const controller = new AbortController();
    const persistence = TestBed.inject(CatalogPersistence);
    const append = persistence.append.bind(persistence);
    vi.spyOn(persistence, 'append').mockImplementationOnce(async (id, rows) => {
      await append(id, rows);
      controller.abort();
    });
    await expect(
      run(previewFixture({ version: '2.0.0' }), controller.signal),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect((await service.browse())?.active).toEqual(before);
    await expect(database.catalogRecords.count()).resolves.toBe(before.recordCount);
  });

  it('reclaims an abandoned stage and fences out its former writer', async () => {
    const persistence = TestBed.inject(CatalogPersistence);
    await run(previewFixture());
    const abandoned = await persistence.begin();
    await persistence.append(abandoned, [{ kind: 'source', record: { id: 'abandoned' } }]);
    await run(previewFixture({ version: '2.0.0' }));
    await expect(persistence.append(abandoned, [])).rejects.toThrow('replaced');
    await expect(
      database.catalogRecords.where('snapshotId').equals(abandoned).count(),
    ).resolves.toBe(0);
  });

  it('preserves both snapshots when the activation transaction hits quota', async () => {
    await run(previewFixture());
    await run(previewFixture({ version: '2.0.0' }));
    const before = await database.catalogState.get('preview');
    const put = database.catalogState.put.bind(database.catalogState);
    vi.spyOn(database.catalogState, 'put').mockImplementation((state) => {
      if (state.active?.catalogVersion === '3.0.0') {
        throw new DOMException('Simulated browser quota failure', 'QuotaExceededError');
      }
      return put(state);
    });
    await expect(run(previewFixture({ version: '3.0.0' }))).rejects.toMatchObject({
      name: 'QuotaExceededError',
    });
    expect(await database.catalogState.get('preview')).toEqual(before);
    await expect(
      database.catalogRecords.where('snapshotId').equals(before!.previous!.id).count(),
    ).resolves.toBe(before!.previous!.recordCount);
  });

  it('preserves the active preview when a staged write hits quota', async () => {
    await run(previewFixture());
    const before = await database.catalogState.get('preview');
    vi.spyOn(database.catalogRecords, 'bulkAdd').mockImplementationOnce(() => {
      throw new DOMException('Simulated browser quota failure', 'QuotaExceededError');
    });
    await expect(run(previewFixture({ version: '2.0.0' }))).rejects.toMatchObject({
      name: 'QuotaExceededError',
    });
    expect(await database.catalogState.get('preview')).toEqual(before);
    await expect(database.catalogRecords.count()).resolves.toBe(before!.active!.recordCount);
  });

  it('restores the previous validated snapshot after reopening', async () => {
    await run(previewFixture());
    const first = (await service.browse())!.active;
    await run(previewFixture({ version: '2.0.0' }));
    database.close();
    await database.open();
    await service.rollback();
    expect((await service.browse())?.active).toEqual(first);
    expect((await service.browse())?.previous?.catalogVersion).toBe('2.0.0');
  });

  function run(files: Map<string, Buffer>, signal = new AbortController().signal) {
    return service.import(selectFiles(files), signal);
  }

  function selectFiles(files: Map<string, Buffer>): CatalogFile[] {
    return [...files].map(([name, bytes]) => ({
      name,
      size: bytes.length,
      arrayBuffer: () => Promise.resolve(Uint8Array.from(bytes).buffer),
    }));
  }
});
