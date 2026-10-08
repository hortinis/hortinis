export const MAXIMUM_SYNCHRONIZATION_ATTEMPTS = 5;
const MAXIMUM_RETRY_DELAY_MILLISECONDS = 8_000;

export type RetryDecision =
  { status: 'exhausted' } | { status: 'retry'; delayMilliseconds: number };

export function synchronizationRetryDecision(
  attemptCount: number,
  sample: (maximumDelayMilliseconds: number) => number,
): RetryDecision {
  if (attemptCount >= MAXIMUM_SYNCHRONIZATION_ATTEMPTS) return { status: 'exhausted' };
  const maximumDelay = Math.min(MAXIMUM_RETRY_DELAY_MILLISECONDS, 1_000 * 2 ** (attemptCount - 1));
  const sampledDelay = sample(maximumDelay);
  if (!Number.isFinite(sampledDelay) || sampledDelay < 0 || sampledDelay > maximumDelay) {
    throw new Error('The synchronization jitter returned an invalid delay.');
  }
  return { status: 'retry', delayMilliseconds: Math.floor(sampledDelay) };
}
