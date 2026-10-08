import { describe, expect, it, vi } from 'vitest';
import { synchronizationRetryDecision } from './synchronization-retry-policy.rule';

describe('synchronization retry policy', () => {
  it.each([
    [1, 1000],
    [2, 2000],
    [3, 4000],
    [4, 8000],
  ])('caps attempt %i at %i milliseconds', (attempt, cap) => {
    const sample = vi.fn((maximum) => maximum);
    expect(synchronizationRetryDecision(attempt, sample)).toEqual({
      status: 'retry',
      delayMilliseconds: cap,
    });
    expect(sample).toHaveBeenCalledExactlyOnceWith(cap);
  });
  it.each([5, 6])('exhausts attempt %i without sampling jitter', (attempt) => {
    const sample = vi.fn();
    expect(synchronizationRetryDecision(attempt, sample)).toEqual({ status: 'exhausted' });
    expect(sample).not.toHaveBeenCalled();
  });
  it.each([0, 123.9])('accepts and floors a valid delay of %s', (sample) => {
    expect(synchronizationRetryDecision(1, () => sample)).toEqual({
      status: 'retry',
      delayMilliseconds: Math.floor(sample),
    });
  });
  it.each([-1, 1001, NaN, Infinity])('rejects invalid jitter %s', (sample) => {
    expect(() => synchronizationRetryDecision(1, () => sample)).toThrow('invalid delay');
  });
});
