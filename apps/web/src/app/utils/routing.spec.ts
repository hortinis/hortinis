import { TestBed } from '@angular/core/testing';
import { Routing } from './routing';

describe('Routing', () => {
  let service: Routing;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(Routing);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
