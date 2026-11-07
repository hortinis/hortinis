import { TestBed } from '@angular/core/testing';
import { ConfigurationPersistence } from './configuration-persistence';

describe('ConfigurationPersistence', () => {
  let service: ConfigurationPersistence;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ConfigurationPersistence);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
