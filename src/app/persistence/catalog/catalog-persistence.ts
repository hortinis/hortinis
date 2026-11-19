import { inject, Service } from '@angular/core';
import {
  type CatalogEntry,
  type CatalogRecordKind,
  type CatalogVersion,
  CatalogImportError,
} from '../../features/catalog-preview/catalog-record';
import { generateUuidV7 } from '../../utils/uuid-v7';
import { HortinisDatabase } from '../hortinis-database';
import { CatalogSnapshotState } from './catalog-records';

const preview = 'preview';

@Service()
export class CatalogPersistence {
  private readonly database = inject(HortinisDatabase);
  private readonly state = this.database.catalogState;
  private readonly records = this.database.catalogRecords;

  async begin(): Promise<string> {
    const id = generateUuidV7();
    await this.database.transaction('rw', this.state, this.records, async () => {
      const state = await this.state.get(preview);
      if (state?.stagingId) {
        await this.deleteSnapshot(state.stagingId);
      }
      await this.state.put({ ...state, id: preview, stagingId: id });
    });
    return id;
  }

  async append(id: string, entries: CatalogEntry[]): Promise<void> {
    await this.database.transaction('rw', this.state, this.records, async () => {
      await this.requireStage(id);
      await this.records.bulkAdd(
        entries.map((entry) => ({ ...entry, id: entry.record.id, snapshotId: id })),
      );
    });
  }

  async activate(
    id: string,
    version: CatalogVersion,
    recordCount: number,
    signal: AbortSignal,
  ): Promise<void> {
    let abortTransaction: (() => void) | undefined;
    try {
      await this.database.transaction('rw', this.state, this.records, async (transaction) => {
        signal.throwIfAborted();
        abortTransaction = () => {
          if (transaction.active) {
            transaction.abort();
          }
        };
        signal.addEventListener('abort', abortTransaction, { once: true });

        const state = await this.requireStage(id);
        if (state.previous) {
          await this.deleteSnapshot(state.previous.id);
        }
        await this.state.put({
          id: preview,
          active: { ...version, id, recordCount },
          previous: state.active,
        });
      });
    } finally {
      if (abortTransaction) {
        signal.removeEventListener('abort', abortTransaction);
      }
    }
  }

  async discard(id: string): Promise<void> {
    await this.database.transaction('rw', this.state, this.records, async () => {
      const state = await this.state.get(preview);
      if (state?.stagingId === id) {
        await this.deleteSnapshot(id);
        await this.state.put({ ...state, stagingId: undefined });
      }
    });
  }

  async rollback(): Promise<void> {
    await this.database.transaction('rw', this.state, async () => {
      const state = await this.state.get(preview);
      if (state?.previous) {
        await this.state.put({ ...state, active: state.previous, previous: state.active });
      }
    });
  }

  async browse() {
    return await this.database.transaction('r', this.state, this.records, async () => {
      const state = await this.state.get(preview);
      if (!state?.active) {
        return undefined;
      }
      const id = state.active.id;
      const [plants, cultivars, groups, names, taxa] = await Promise.all([
        this.byKind(id, 'plant-concept'),
        this.byKind(id, 'cultivar'),
        this.byKind(id, 'cultivar-group'),
        this.byKind(id, 'localized-name'),
        this.byKind(id, 'taxon'),
      ]);
      return {
        active: state.active,
        previous: state.previous,
        subjects: [...plants, ...cultivars, ...groups],
        names,
        taxa,
      };
    });
  }

  async withSubject<T>(
    snapshotId: string,
    subjectId: string,
    project: (
      entries: CatalogEntry[],
      get: (id: string) => Promise<CatalogEntry | undefined>,
    ) => Promise<T>,
  ): Promise<T | undefined> {
    return await this.database.transaction('r', this.state, this.records, async () => {
      if ((await this.state.get(preview))?.active?.id !== snapshotId) {
        return undefined;
      }
      const entries = await this.records
        .where('[snapshotId+subjectId]')
        .equals([snapshotId, subjectId])
        .toArray();
      return await project(entries, (id) => this.records.get([snapshotId, id]));
    });
  }

  private byKind(snapshotId: string, kind: CatalogRecordKind) {
    return this.records.where('[snapshotId+kind]').equals([snapshotId, kind]).toArray();
  }

  private async requireStage(id: string): Promise<CatalogSnapshotState> {
    const state = await this.state.get(preview);
    if (state?.stagingId !== id) {
      throw new CatalogImportError('A different catalog import replaced this staged snapshot.');
    }
    return state;
  }

  private async deleteSnapshot(id: string): Promise<void> {
    await this.records.where('snapshotId').equals(id).delete();
  }
}
