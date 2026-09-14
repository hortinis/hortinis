import { Injectable } from '@angular/core';
import Dexie from 'dexie';

@Injectable({ providedIn: 'root' })
export class HortinisDatabase extends Dexie {
  constructor() {
    super('hortinis');

    this.version(1).stores({});
  }
}
