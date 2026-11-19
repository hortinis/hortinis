import type { recordKinds } from './catalog-record-kinds.mjs';

export type CatalogRecordKind = (typeof recordKinds)[number] | 'source-manifest';
export type CatalogObject = Record<string, unknown>;
export type CatalogRecord = CatalogObject & { id: string };

export interface CatalogEntry {
  kind: CatalogRecordKind;
  record: CatalogRecord;
  subjectId?: string;
}

export interface CatalogVersion {
  catalogVersion: string;
  generatedAt: string;
  profile: string;
  schemaVersion: string;
}

export function object(value: unknown): CatalogObject {
  return value != null && typeof value === 'object' ? (value as CatalogObject) : {};
}

export function strings(value: unknown): string[] {
  return Array.isArray(value) ? (value as string[]) : [];
}

export class CatalogImportError extends Error {}
