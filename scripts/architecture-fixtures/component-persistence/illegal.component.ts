import { Component } from '@angular/core';
import { HortinisDatabase } from '../../../src/app/persistence/hortinis-database';

@Component({ selector: 'hortinis-illegal', template: '' })
export class IllegalComponent {
  constructor(database: HortinisDatabase) {
    void database;
  }
}
