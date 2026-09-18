import { TestBed } from '@angular/core/testing';
import { Device } from './device';

describe('Device', () => {
  let service: Device;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(Device);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
