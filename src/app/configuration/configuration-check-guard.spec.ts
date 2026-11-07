import { TestBed } from '@angular/core/testing';
import { CanActivateFn } from '@angular/router';
import { configurationCheckGuard } from './configuration-check-guard';

describe('configurationCheckGuard', () => {
  const executeGuard: CanActivateFn = (...guardParameters) =>
    TestBed.runInInjectionContext(() => configurationCheckGuard(...guardParameters));

  beforeEach(() => {
    TestBed.configureTestingModule({});
  });

  it('should be created', () => {
    expect(executeGuard).toBeTruthy();
  });
});
