import { TestBed } from '@angular/core/testing';
import { GardenPersistence } from './garden-persistence';

describe('GardenPersistence', () => {
  let service: GardenPersistence;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GardenPersistence);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
