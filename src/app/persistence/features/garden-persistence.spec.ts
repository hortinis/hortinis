import 'fake-indexeddb/auto';
import { TestBed } from '@angular/core/testing';
import { GardenPersistence, RecordNotFound } from './garden-persistence';
import { HortinisDatabase } from '../hortinis-database';

describe('GardenPersistence', () => {
  let database: HortinisDatabase;
  let service: GardenPersistence;

  beforeEach(() => {
    database = new HortinisDatabase(`hortinis-gardens-${crypto.randomUUID()}`);
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    service = TestBed.inject(GardenPersistence);
  });

  afterEach(async () => {
    database.close();
    await database.delete();
  });

  it('creates gardens locally and retains them after reopening', async () => {
    const first = await service.createGarden({ name: 'Kitchen garden' });
    const second = await service.createGarden({ name: 'Orchard' });
    expect(first.id).not.toBe(second.id);
    expect(first.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    database.close();
    await database.open();
    await expect(service.listEnabledGardens()).resolves.toEqual([first, second]);
  });

  it('hides a deleted garden while retaining independent gardens', async () => {
    const first = await service.createGarden({ name: 'Kitchen garden' });
    const second = await service.createGarden({ name: 'Orchard' });
    await service.deleteGarden(first);
    await expect(service.listEnabledGardens()).resolves.toEqual([second]);
    await expect(database.garden.get(first.id)).resolves.toEqual({ ...first, enabled: false });
  });

  it('rejects deletion of a missing garden', async () => {
    const garden = await service.createGarden({ name: 'Kitchen garden' });
    await database.garden.delete(garden.id);
    await expect(service.deleteGarden(garden)).rejects.toBeInstanceOf(RecordNotFound);
  });
});
