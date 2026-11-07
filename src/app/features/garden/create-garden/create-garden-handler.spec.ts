import { TestBed } from '@angular/core/testing';
import { CreateGardenHandler } from './create-garden-handler';

describe('CreateGardenHandler', () => {
  let service: CreateGardenHandler;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(CreateGardenHandler);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
