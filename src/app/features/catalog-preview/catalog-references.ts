import {
  CatalogEntry,
  CatalogImportError,
  CatalogObject,
  CatalogRecordKind,
  object,
  strings,
} from './catalog-record';

interface Reference {
  id: string;
  kind?: CatalogRecordKind;
  release?: string;
  purpose?: 'content' | 'rights';
}

interface RecordLinks {
  kind: CatalogRecordKind;
  references: Reference[];
  release?: string;
  purpose?: string;
}

export class CatalogReferences {
  private readonly records = new Map<string, RecordLinks>();

  add({ kind, record }: CatalogEntry): void {
    if (this.records.has(record.id)) {
      throw new CatalogImportError('The catalog contains a duplicate record ID.');
    }
    const references: Reference[] = [];
    const add = (id: unknown, expectedKind?: CatalogRecordKind, purpose?: Reference['purpose']) => {
      if (typeof id === 'string') {
        references.push({ id, kind: expectedKind, purpose });
      }
    };
    const addAll = (ids: unknown, expectedKind?: CatalogRecordKind) => {
      for (const id of strings(ids)) {
        add(id, expectedKind);
      }
    };
    const addSubject = (value: unknown) => {
      const subject = object(value);
      add(subject['id'], subject['type'] as CatalogRecordKind);
    };
    const addSource = (value: CatalogObject) => {
      add(value['sourceId'], 'source');
      if (typeof value['sourceManifestId'] === 'string') {
        references.push({
          id: value['sourceManifestId'],
          kind: 'source-manifest',
          release: value['sourceReleaseId'] as string,
        });
      }
    };

    switch (kind) {
      case 'plant-concept':
        add(record['taxonId'], 'taxon');
        add(record['parentPlantConceptId'], 'plant-concept');
        add(record['replacementPlantConceptId'], 'plant-concept');
        break;
      case 'cultivar':
        add(record['plantConceptId'], 'plant-concept');
        addAll(record['cultivarGroupIds'], 'cultivar-group');
        add(record['replacementCultivarId'], 'cultivar');
        break;
      case 'cultivar-group':
        add(record['plantConceptId'], 'plant-concept');
        add(record['replacementCultivarGroupId'], 'cultivar-group');
        break;
      case 'taxon':
        add(record['parentTaxonId'], 'taxon');
        add(record['replacementTaxonId'], 'taxon');
        break;
      case 'taxonomic-name':
        add(record['taxonId'], 'taxon');
        add(record['acceptedTaxonomicNameId'], 'taxonomic-name');
        add(record['replacementTaxonomicNameId'], 'taxonomic-name');
        add(record['reviewId'], 'review', 'content');
        addSource(object(record['externalIdentifier']));
        break;
      case 'localized-name':
        addSubject(record['subject']);
        break;
      case 'plant-fact':
      case 'cultivation-rule':
      case 'relationship':
        addSubject(record['subject']);
        add(record['contextId'], 'cultivation-context');
        add(record['reviewId'], 'review', 'content');
        if (kind === 'plant-fact') {
          addAll(record['supersedesFactIds'], kind);
          if (record['predicate'] === 'days_to_first_harvest') {
            add(
              object(object(record['value'])['anchorInterpretation'])['reviewId'],
              'review',
              'content',
            );
          }
        } else if (kind === 'cultivation-rule') {
          addAll(record['supersedesRuleIds'], kind);
        } else {
          addSubject(record['target']);
          addAll(record['supersedesRelationshipIds'], kind);
        }
        break;
      case 'cultivation-context':
        addAll(object(record['geographicScope'])['geographicContextIds'], 'geographic-context');
        break;
      case 'geographic-context':
        addAll(record['parentGeographicContextIds'], 'geographic-context');
        break;
      case 'evidence-reference': {
        addSource(record);
        addSource(object(object(record['sourceRecordKey'])['source']));
        const rights = object(record['rights']);
        add(rights['licenceId'], 'licence');
        add(rights['reviewId'], 'review', 'rights');
        break;
      }
      case 'attribution':
        add(record['sourceId'], 'source');
        add(record['licenceId'], 'licence');
        addAll(record['includedRecordIds']);
        break;
    }

    if (
      ![
        'review',
        'source-manifest',
        'source',
        'licence',
        'attribution',
        'evidence-reference',
      ].includes(kind)
    ) {
      addAll(record['evidenceReferenceIds'], 'evidence-reference');
    }
    this.records.set(record.id, {
      kind,
      references,
      release:
        kind === 'source-manifest'
          ? (object(record['release'])['identifier'] as string)
          : undefined,
      purpose: kind === 'review' ? (record['purpose'] as string) : undefined,
    });
  }

  validate(): void {
    for (const record of this.records.values()) {
      for (const reference of record.references) {
        const target = this.records.get(reference.id);
        if (
          !target ||
          (reference.kind && target.kind !== reference.kind) ||
          (reference.release !== undefined && reference.release !== target.release) ||
          (reference.purpose !== undefined && reference.purpose !== target.purpose)
        ) {
          throw new CatalogImportError(
            `Broken ${reference.kind ?? 'record'} reference in a ${record.kind} record.`,
          );
        }
      }
    }
  }
}
