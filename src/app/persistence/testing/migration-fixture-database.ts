import Dexie, { Table, Transaction } from 'dexie';

interface LegacyMigrationProbe {
  id?: number;
  createdAt: string;
}

export type MigrationProbe = LegacyMigrationProbe & {
  migrationState: 'migrated';
};

const schemaV1 = {
  migrationProbes: '++id, createdAt',
};

const schemaV2 = {
  migrationProbes: '++id, createdAt, migrationState',
};

async function migrateToV2(transaction: Transaction): Promise<void> {
  await transaction.table('migrationProbes').toCollection().modify({ migrationState: 'migrated' });
}

export class LegacyMigrationFixtureDatabase extends Dexie {
  readonly migrationProbes!: Table<LegacyMigrationProbe, number>;

  constructor(databaseName: string) {
    super(databaseName);
    this.version(1).stores(schemaV1);
  }
}

export class MigrationFixtureDatabase extends Dexie {
  readonly migrationProbes!: Table<MigrationProbe, number>;

  constructor(databaseName: string) {
    super(databaseName);
    this.version(1).stores(schemaV1);
    this.version(2).stores(schemaV2).upgrade(migrateToV2);
  }
}
