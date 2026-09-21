# ADR-0026: Indeterminate operation outcomes and full reconciliation

- Status: Accepted

## Context

A client can lose an acknowledgement after the server accepts an operation. If the corresponding receipt
is later compacted and the generation rolls over, neither retry nor absence of a receipt proves whether
the operation took effect. Treating the operation as new can duplicate effects; treating it as accepted
can discard local intent.

Full reconciliation must also compare the server snapshot with the client's last-synchronized base and
pending journal without embedding future garden-specific merge rules in the technical protocol.

## Decision

### Indeterminate operations

The server returns an `indeterminate` operation outcome when it recognizes an operation from a retired
generation but no longer has sufficient evidence to prove acceptance or rejection. The outcome contains
the operation identifier, submitted generation, current generation, and reason
`receipt_unavailable`. It does not claim a server revision or sequence.

The client moves the exact immutable operation and its local intent from automatic submission to durable
indeterminate work. It retains the operation identifier, original generation, original validated fields,
causal predecessor information, and affected local/base state. It never changes the operation's
identifier or generation, automatically submits it in the new generation, or infers acceptance merely
because current server state looks equivalent.

Only an explicit reconciliation result or future user-authorized domain workflow may resolve
indeterminate work. Generic reconciliation can show that intent is already reflected in server state,
contradicts server state, or remains unverifiable, but semantic side effects prevent it from declaring an
unknown operation accepted solely from projection equality.

### Generic three-way reconciliation

Reconciliation runs locally after the complete anchored snapshot is available. Its inputs are:

- the complete server snapshot at the anchor;
- the client's last-synchronized base;
- pending operations and their causal chains; and
- durable indeterminate operations and existing conflicts.

The generic unit of comparison is stable record identity, not an individual domain field. The safe
outcomes are:

- `adopt_server`: the client has no pending or indeterminate intent for the record;
- `preserve_pending`: server state still equals the relevant base and a pending causal chain can be
  preserved for explicit rebasing into the current generation;
- `conflict`: server state and pending local intent both differ from the base, including deletion versus
  replacement or creation against a retired identifier;
- `keep_indeterminate`: an operation's acceptance remains unprovable.

Changes to different record identities are non-overlapping and may be combined. Field-level merging,
operation-semantic equivalence, and automatic resolution within one record require domain-specific rules
and are excluded from this decision.

The reconciliation result is committed atomically with the new server-derived base, current generation,
and incremental cursor. Pending, conflicting, and indeterminate intent remains durable. A crash before
that commit leaves the previous base and reconciliation progress usable for a complete retry.

### Required wire behavior

The production protocol defines closed schemas for:

- a generation-bound operation envelope;
- `RECONCILIATION_REQUIRED`;
- an `indeterminate` operation outcome;
- reconciliation-session start and anchored snapshot pages;
- technical tombstone entries; and
- generic per-record reconciliation outcomes used by cross-runtime conformance tests.

The TypeSpec contract may expose these production schemas before G2 through G4 activate their adapters.
The walking-skeleton request models remain valid only for the explicitly documented V0 topology during
that transition.

## Consequences

- Local intent survives the strongest ambiguous failure case in ADR-0010.
- Projection equality is useful evidence for presentation but not proof of operation acceptance.
- The technical protocol can merge changes to different records without choosing garden merge rules.
- Product work must later define how users inspect and resolve record-level conflicts and indeterminate
  operations.
- G3 implements durable indeterminate state. G4 implements generic reconciliation. G5 exercises restart
  and repetition across both.

## Rejected alternatives

- **Resubmit under the active generation.** The original operation may already have taken effect.
- **Assume matching state proves acceptance.** Another operation may have produced the same projection,
  and domain operations may have effects not visible in that projection.
- **Discard old local intent.** This violates the offline-first and ADR-0010 guarantees.
- **Define field-level last-write-wins.** It silently resolves domain conflicts without an accepted
  product rule.

## Validation criteria

- Lost acknowledgement followed by receipt compaction and rollover creates durable indeterminate work.
- Automatic recovery sends no request that changes the indeterminate operation's generation or identity.
- Reconciliation combines changes to different record identities.
- Concurrent changes to one identity remain explicit, including deletion conflicts.
- Repeating reconciliation after a crash produces the same outcomes and does not lose pending work.
