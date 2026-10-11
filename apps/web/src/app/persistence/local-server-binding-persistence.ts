import { inject, Injectable } from '@angular/core';
import { parseAccessContract, type LocalServerBinding } from '../sync/access-contracts.rule';
import { HortinisDatabase } from './hortinis-database';
import { LocalDataSetUnavailableError } from './local-data-set-identity';
import { readLocalBinding, sameBinding } from './local-exchange-metadata';

@Injectable({ providedIn: 'root' })
export class LocalServerBindingPersistence {
  private readonly database = inject(HortinisDatabase);

  async current(): Promise<LocalServerBinding | undefined> {
    return this.database.transaction(
      'r',
      this.database.localDataSetIdentity,
      this.database.localServerBindings,
      async () => {
        const identity = await this.database.requireLocalDataSetId();
        const rows = await this.database.localServerBindings.toArray();
        if (rows.length === 0) return undefined;
        if (rows.length !== 1) throw new LocalDataSetUnavailableError();
        const binding = parseAccessContract('LocalServerBinding', rows[0]);
        if (binding.localDataSetId !== identity) throw new LocalDataSetUnavailableError();
        return binding;
      },
    );
  }

  // Storage only: server discovery/confirmation and exchange activation belong to S6.
  async put(value: LocalServerBinding): Promise<void> {
    const binding = parseAccessContract('LocalServerBinding', value);
    await this.database.transaction(
      'rw',
      this.database.localDataSetIdentity,
      this.database.localServerBindings,
      async () => {
        if (binding.localDataSetId !== (await this.database.requireLocalDataSetId())) {
          throw new LocalDataSetUnavailableError();
        }
        const current = await this.database.localServerBindings.toArray();
        if (
          current.length > 1 ||
          current.some(
            (row) =>
              parseAccessContract('LocalServerBinding', row).localDataSetId !==
              binding.localDataSetId,
          )
        ) {
          throw new LocalDataSetUnavailableError();
        }
        const existing = readLocalBinding(current, binding.localDataSetId);
        if (existing && !sameBinding(existing, binding)) throw new LocalDataSetUnavailableError();
        await this.database.localServerBindings.put(binding);
      },
    );
  }
}
