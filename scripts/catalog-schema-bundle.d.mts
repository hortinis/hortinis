export function readCatalogSchemaBundle(): {
  readonly $schema: string;
  readonly $id: string;
  readonly $defs: Record<string, { readonly $id: string; title?: string }>;
};
