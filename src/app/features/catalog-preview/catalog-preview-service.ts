import { inject, Service } from '@angular/core';
import { CatalogPersistence } from '../../persistence/catalog/catalog-persistence';
import { CatalogReferences } from './catalog-references';
import { CatalogEntry, CatalogImportError, CatalogRecord, object, strings } from './catalog-record';
import { CatalogFile, catalogEntries, verifyCatalogFiles } from './catalog-release';

const batchBytes = 2 * 1024 * 1024;
const batchEntries = 100;

@Service()
export class CatalogPreviewService {
  private readonly persistence = inject(CatalogPersistence);

  async import(files: readonly CatalogFile[], signal: AbortSignal): Promise<void> {
    const { manifest, selected } = await verifyCatalogFiles(files, signal);
    signal.throwIfAborted();
    const stage = await this.persistence.begin();

    try {
      const references = new CatalogReferences();
      let batch: CatalogEntry[] = [];
      let bytes = 0;
      let count = 0;
      let subjects = 0;
      for await (const { entry, bytes: entryBytes } of catalogEntries(selected, signal)) {
        references.add(entry);
        if (batch.length && (bytes + entryBytes > batchBytes || batch.length >= batchEntries)) {
          await this.persistence.append(stage, batch);
          batch = [];
          bytes = 0;
        }
        batch.push(entry);
        bytes += entryBytes;
        count++;
        if (entry.kind === 'plant-concept') {
          subjects++;
        }
      }
      if (!subjects) {
        throw new CatalogImportError('The preview does not contain any plant concepts.');
      }
      if (batch.length) {
        await this.persistence.append(stage, batch);
      }
      signal.throwIfAborted();
      references.validate();
      const { schemaVersion, catalogVersion, profile, generatedAt } = manifest;
      await this.persistence.activate(
        stage,
        { schemaVersion, catalogVersion, profile, generatedAt },
        count,
        signal,
      );
    } catch (error) {
      // If storage is unavailable, the next import replaces the inaccessible stage.
      await this.persistence.discard(stage).catch(() => undefined);
      throw error;
    }
  }

  rollback(): Promise<void> {
    return this.persistence.rollback();
  }

  async browse() {
    const catalog = await this.persistence.browse();
    if (!catalog) {
      return undefined;
    }
    const names = new Map<string, CatalogRecord>();
    const htmlLanguage = 'en';
    const rank = (name: CatalogRecord) =>
      (String(name['languageTag']).split('-')[0] === htmlLanguage ? 2 : 0) +
      (name['preferred'] === true ? 1 : 0);
    for (const { subjectId, record } of catalog.names) {
      if (!subjectId) {
        continue;
      }
      const current = names.get(subjectId);
      if (!current || rank(record) > rank(current)) {
        names.set(subjectId, record);
      }
    }
    const taxa = new Map(
      catalog.taxa.map(({ record }) => [record.id, record['scientificName'] as string]),
    );
    const subjects = catalog.subjects
      .map(({ kind, record }) => ({
        id: record.id,
        status: record['status'] as string,
        kind:
          kind === 'plant-concept' ? ((record['kind'] as string | undefined) ?? 'generic') : kind,
        label:
          (record['denomination'] as string | undefined) ??
          (names.get(record.id)?.['value'] as string | undefined) ??
          (object(record['supplierAccession'])['label'] as string | undefined) ??
          taxa.get(record['taxonId'] as string) ??
          record.id,
        parentId: (record['parentPlantConceptId'] ?? record['plantConceptId']) as
          string | undefined,
      }))
      .sort(
        (a, b) =>
          a.label.localeCompare(b.label, htmlLanguage) || a.id.localeCompare(b.id, htmlLanguage),
      );
    return { active: catalog.active, previous: catalog.previous, subjects };
  }

  subject(snapshotId: string, subjectId: string) {
    return this.persistence.withSubject(snapshotId, subjectId, async (entries, get) => {
      const pending = new Map<string, Promise<CatalogRecord | undefined>>();
      const record = (id: unknown) => {
        if (typeof id !== 'string') {
          return Promise.resolve(undefined);
        }
        let lookup = pending.get(id);
        if (!lookup) {
          lookup = get(id).then((value) => value?.record);
          pending.set(id, lookup);
        }
        return lookup;
      };
      const evidence = async (id: string) => {
        const item = (await record(id))!;
        const rights = object(item['rights']);
        const key = object(item['sourceRecordKey']);
        const sourceKey = item['sourceRecordKey'] ? object(key['source']) : item;
        const [source, licence, review] = await Promise.all([
          record(sourceKey['sourceId']),
          record(rights['licenceId']),
          record(rights['reviewId']),
        ]);
        return {
          id,
          source: source?.['name'],
          sourceId: sourceKey['sourceId'],
          sourceRecordId: key['recordId'] ?? item['sourceRecordId'],
          sourceReleaseId: sourceKey['sourceReleaseId'],
          sourceManifestId: sourceKey['sourceManifestId'],
          locator: item['locator'],
          normalization: item['normalization'],
          decision: rights['decision'],
          reason: rights['reason'],
          licence: licence?.['name'],
          licenceExpression: licence?.['expression'],
          notice: licence?.['requiredNotice'],
          reviewStatus: review?.['status'],
          reviewId: rights['reviewId'],
        };
      };
      const claims = entries.filter(
        ({ kind }) =>
          kind === 'plant-fact' || kind === 'cultivation-rule' || kind === 'relationship',
      );
      const identity = await record(subjectId);
      if (!identity) {
        return undefined;
      }
      const identityEvidence = Promise.all(strings(identity['evidenceReferenceIds']).map(evidence));
      const details = await Promise.all(
        claims.map(async ({ kind, record: claim }) => {
          const [context, review, provenance] = await Promise.all([
            record(claim['contextId']),
            record(claim['reviewId']),
            Promise.all(strings(claim['evidenceReferenceIds']).map(evidence)),
          ]);
          const scope = object(context?.['geographicScope']);
          const geography = await Promise.all(strings(scope['geographicContextIds']).map(record));
          return {
            id: claim.id,
            kind,
            title: claim['predicate'] ?? claim['action'] ?? claim['relation'],
            raw: claim,
            context,
            geography,
            geographyType: scope['type'],
            review: {
              id: review?.id,
              status: review?.['status'],
              confidence: review?.['confidence'],
            },
            provenance,
          };
        }),
      );
      return {
        identity,
        evidence: await identityEvidence,
        names: entries.filter(({ kind }) => kind === 'localized-name').map(({ record }) => record),
        claims: details,
      };
    });
  }
}
