/** Loaded before Angular: action secrets never reach routing, recovery or diagnostics. */
let action: { kind: 'invitation' | 'reset'; token: string } | undefined;

export function captureActionFragment(): void {
  const kind =
    location.pathname === '/accept-invitation'
      ? 'invitation'
      : location.pathname === '/reset-password'
        ? 'reset'
        : undefined;
  const fragment = location.hash;
  if (fragment) history.replaceState(null, '', location.pathname + location.search);
  if (!kind) return;
  const match = /^#token=([A-Za-z0-9_-]{42}[AEIMQUYcgkosw048])$/.exec(fragment);
  action = { kind, token: match?.[1] ?? '' };
}

export function takeActionFragment(): typeof action {
  const value = action;
  action = undefined;
  return value;
}
