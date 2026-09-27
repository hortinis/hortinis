# G3 implementation plan

## Purpose

This document is the implementation handoff plan for G3, **Implement generation rollover and
indeterminate outcomes**. It is intentionally detailed enough for the work to be split across multiple
development sessions without rediscovering protocol decisions or sequencing constraints.

G3 must start from `origin/feat/g2e-validation` at commit `891dcae` (`Validate complete tombstone
recovery`). That branch contains the validated G2 implementation and is the required predecessor for
this work. Do not start G3 from the older `feat/d2-technical-sync-contracts` checkout.

The authoritative scope remains the G3 entry in
[`foundation-implementation-plan.md`](foundation-implementation-plan.md), ADR-0023, ADR-0026, and the
production synchronization traceability matrix. This plan refines that scope; it does not authorize G4
anchored snapshots or full reconciliation.

## Outcome

G3 associates incremental client state and every first-submitted operation with an opaque server-owned
synchronization generation. The server can roll the scope to a new generation before removing history
or receipts. A client using retired incremental state is told explicitly that reconciliation is required.
If an operation from a retired generation no longer has enough retained evidence to prove acceptance or
rejection, the server returns an indeterminate outcome and the browser preserves the exact operation and
its local intent as durable work.

The critical acceptance scenario is:

1. the browser submits a generation-bound operation;
2. the server accepts it, but the acknowledgement is lost;
3. the server rolls the synchronization generation and compacts the corresponding receipt;
4. the browser retries the unchanged operation identifier, generation, and validated fields;
5. the server does not execute it again and returns an indeterminate outcome;
6. the browser atomically removes it from automatic submission and preserves it as durable indeterminate
   work without discarding its projection, base, causal successors, or conflicts.

## Existing foundation and identified contract gap

The G1 contract already contains standalone schemas and canonical fixtures for:

- `GenerationBoundOperation`;
- `IndeterminateOperationOutcome`;
- `ReconciliationRequiredError`;
- reconciliation and anchored-snapshot types owned by G4.

The runnable G2 endpoints still accept an unbound operation, return only accepted results, and return a
change page without an explicit generation. They also have no HTTP mapping for
`RECONCILIATION_REQUIRED`. Consequently, a fresh browser cannot learn the active generation before its
first submission.

G3 must close this contract gap before changing either runtime. Merely wrapping push requests is not
sufficient: bootstrap, incremental cursor expiry, and operation submission must use one coherent
generation boundary.

## Scope boundaries

G3 includes:

- generation-bound operation submission;
- generation-bound incremental cursors and change pages;
- initial generation discovery while complete incremental history is available;
- durable server generation state and transactional rollover;
- retained-receipt replay from a retired generation;
- indeterminate outcomes when retired-generation evidence is unavailable;
- durable browser indeterminate and reconciliation-required state;
- preservation of pending local work, causal information, and available last-synchronized base state;
- migration, restart, reload, retry, and cross-runtime validation for those behaviors.

G3 excludes:

- reconciliation-session and anchored-snapshot runtime adapters;
- generic three-way reconciliation;
- rebasing pending operations into a new generation;
- user-facing domain resolution of indeterminate work or conflicts;
- configured retention scheduling and the complete forced-compaction operational matrix owned by G5;
- a public or unauthenticated administrative rollover endpoint;
- authentication, authorization, and production synchronization-scope selection.

## Contract decisions to record first

The following refinements should be recorded in ADR-0023 and the API documentation before runtime code
depends on them:

1. `POST /api/v1/sync/operations` accepts `GenerationBoundOperation` rather than the unbound operation
   union.
2. A successful HTTP response is a closed union of an accepted `OperationResult` and an
   `IndeterminateOperationOutcome`. An indeterminate outcome uses HTTP 200 because it is a terminal,
   successfully interpreted submission outcome rather than a transport failure.
3. `ChangePage` includes the active `generation`. This allows a successful initial pull to establish
   the generation and cursor as one persisted boundary.
4. `GET /api/v1/sync/changes` returns `RECONCILIATION_REQUIRED` as HTTP 409. The client state conflicts
   with the server's retained incremental boundary; this is distinct from malformed input and from the
   G4 `SNAPSHOT_EXPIRED` response.
5. A current-generation cursor may return an empty page. A retired-generation cursor returns
   `generation_expired`; unavailable required history returns `history_compacted`; and a cursor that
   cannot be continued within its recognized generation returns `cursor_expired`.
6. A pull without a cursor may bootstrap a client only while the server retains complete incremental
   history from the synchronization scope's origin. After that history is compacted, it returns
   `RECONCILIATION_REQUIRED`; G4 will provide the required full snapshot.
7. The existing sequence-only `v1` cursor is accepted only as a migration bridge in the initial
   generation. A successful pull returns the new generation-bound cursor. It must not be accepted after
   rollover or transferred between scopes.
8. Operations created locally before a generation is known remain unbound and are not submitted. The
   browser performs an initial pull, atomically persists the returned generation and cursor, and binds an
   operation immediately before its first submission.
9. Detecting the server's new current generation does not replace the client's submitted generation or
   rebind pending operations. That transition is owned by G4 reconciliation.

If review selects a different HTTP status or bootstrap representation, resolve it in the contract and
ADR before proceeding. Do not let the Java and TypeScript implementations make different implicit
choices.

## Increment G3a: activate the generation wire contract

### Work

- Change the operation endpoint request to `GenerationBoundOperation`.
- Add a named closed operation-submission outcome union containing accepted and indeterminate outcomes.
- Add `generation` to `ChangePage`.
- Add the 409 `ReconciliationRequiredError` response to incremental pull.
- Update descriptions so the generated contract no longer describes generation binding as future work.
- Add focused invalid examples for missing, empty, extended, and mismatched generation envelopes;
  malformed indeterminate outcomes; and extended reconciliation-required errors.
- Regenerate OpenAPI and standalone JSON Schemas from TypeSpec.
- Promote `generation-bound-delete.json`, `indeterminate-operation.json`, and
  `reconciliation-required.json` from contract-only planned fixtures to G3 runtime fixtures with both
  TypeScript and Java consumers.
- Keep reconciliation-session and snapshot fixtures planned for G4.

### Acceptance

- Generated artifacts are byte-for-byte reproducible.
- OpenAPI and JSON Schema agree on the new request and response unions.
- Unknown properties and invalid union combinations are rejected.
- The fixture capability manifest fails if either runtime stops consuming an implemented G3 fixture.
- Existing create, replace, delete, result, tombstone, and change shapes remain structurally unchanged
  inside their new generation context.

### Validation

Run the contract formatting, generation drift, lint, schema, negative-fixture, and cross-runtime
conformance commands documented in [`README.md`](README.md).

## Increment G3b: persist server generations and implement rollover

### Database migration

Add the next Flyway migration after G2's V3 schema. It should:

- create a synchronization-generation table with an opaque generation identifier, lifecycle state, and
  server timestamp metadata;
- add the active generation reference to `technical_synchronization_scope`;
- create and activate one initial generation for the existing technical scope;
- add a non-null generation reference to accepted operation receipts and technical changes;
- backfill existing receipts and changes into the initial generation;
- add the constraints and indexes needed for receipt lookup, generation validation, ordered change pull,
  and bounded compaction;
- retain lifetime record-identifier reservations independently from compactable tombstone details.

Use an opaque server-controlled value without client-visible ordering semantics. Do not add a new
database extension solely to generate it if the existing Java/application boundary can supply the value.

### Cursor behavior

Introduce a version-two opaque cursor carrying a generation identifier and sequence position. The codec
must:

- round-trip canonical values;
- reject malformed, non-canonical, unknown-scope, and impossible positions;
- recognize the old `v1` sequence-only cursor only during the initial-generation migration window;
- return a typed decoded boundary rather than leaking cursor representation into the service;
- distinguish invalid syntax from a recognized but no-longer-usable generation.

### Operation submission

The server submission transaction should follow this order:

1. validate the complete generation envelope and inner operation;
2. lock/read the synchronization scope and current generation;
3. if the submitted generation is current, execute the existing idempotent operation workflow;
4. if it is retired, look up retained evidence before returning an outcome;
5. return the stable result when an identical retained receipt proves acceptance;
6. return retained terminal rejection evidence when such evidence exists;
7. otherwise return `IndeterminateOperationOutcome` without changing records, tombstones, reservations,
   revisions, sequences, receipts, or changes;
8. never reinterpret a retired-generation operation as new work in the active generation.

Receipt identity includes the original generation and all validated operation fields. Reusing an
operation identifier with a different generation or operation remains an explicit identifier-reuse
error when retained evidence can prove the mismatch.

### Rollover primitive

Implement a service-level transactional rollover primitive that:

- serializes with operation acceptance and change publication by locking the scope boundary;
- creates the new generation and retires the previous generation;
- makes the new generation active before removing history or receipts required by the old generation;
- can compact selected retired-generation changes, tombstone details, and receipts without removing
  current projections or lifetime identifier reservations;
- rolls back the generation switch and compaction together on failure;
- persists state across service restart;
- emits a privacy-safe operational event containing no identifiers, operation data, record values,
  request bodies, or response bodies.

Do not add a public HTTP rollover endpoint. PostgreSQL integration tests can invoke the service-level
primitive directly. Configured scheduling and an operator-facing control mechanism require separate
operational design and remain outside G3.

### Server acceptance tests

Cover at least:

- current-generation create, replace, delete, identical replay, and operation-ID reuse;
- pull with no cursor, a current cursor, an empty page, and a migrated `v1` cursor;
- ordered pages that return the same generation and generation-bound next cursors;
- retired-generation replay while its receipt and result remain;
- retired-generation submission after receipt/result compaction returning indeterminate;
- no database mutation or new sequence for an indeterminate operation;
- retired, compacted, and expired cursor reasons;
- rollover serialization against concurrent operation acceptance;
- rollback at the generation switch and compaction boundaries;
- populated V3-to-new-schema migration;
- service restart with active and retired generations;
- privacy-safe diagnostics.

## Increment G3c: migrate browser state and bind operations

### Dexie migration

Add a new Dexie schema version after G2's version 4. Preserve every existing table and add the minimum
stores needed for:

- durable indeterminate operations;
- explicit last-synchronized base state where it is not already recoverable;
- durable reconciliation-required state, either in the synchronization-state record or a dedicated
  narrowly scoped store.

Extend the local synchronization boundary to contain:

- scope;
- persisted generation when known;
- persisted incremental cursor when known;
- reconciliation-required status, current server generation, and reason when detected.

Existing version-four data has a cursor but no explicit generation. Preserve that cursor unchanged and
leave its generation unknown until the server accepts the migration bridge and returns a complete new
boundary. Never invent a generation during IndexedDB migration.

### Local operations

Represent a submit-ready operation as the immutable pair of generation and existing technical operation.
Operations may remain unbound while they have never been submitted. The persistence layer must:

- create offline operations without requiring network state;
- select no submit-ready operation while the scope generation is unknown;
- atomically bind the selected operation to the current locally persisted generation before transport;
- preserve that binding through unavailable responses, timeouts, bounded retry, reload, manual recovery,
  and ownership transfer;
- preserve predecessor links and derived expected revisions;
- compare submitted and persisted envelopes structurally before committing any response.

The recovery coordinator should bootstrap with a pull only when no generation is known. Once a
generation is known, the established push-then-pull order can remain until a terminal generation outcome
requires reconciliation.

### Last-synchronized base

ADR-0026 requires indeterminate work and G4 reconciliation to retain the relevant last-synchronized
base. G3 should begin storing that base explicitly when accepting or pulling records and tombstones.

For migrated data:

- derive a base only when the stored state proves it is safe to do so;
- when a pending legacy operation has already overlaid the previous server value and the exact base
  cannot be reconstructed, store an explicit unavailable marker;
- never copy a local edited value and label it as an accepted server base.

## Increment G3d: preserve indeterminate and reconciliation-required work

### Durable indeterminate record

Persist enough information to satisfy ADR-0026 without consulting transient service state:

- the exact immutable generation-bound operation;
- operation and record identifiers;
- predecessor operation identifier and causal-chain information where present;
- submitted generation and server-reported current generation;
- reason `receipt_unavailable`;
- the affected local intent;
- the available last-synchronized record or tombstone base, or an explicit unavailable marker;
- any state needed to reconnect deferred successors to this unresolved predecessor.

Do not store credentials, tokens, request diagnostics, timestamps derived from private content, or whole
HTTP request/response bodies.

### Atomic indeterminate transition

When the transport returns an indeterminate outcome, use one IndexedDB transaction to:

1. validate that the outcome matches the submitted operation identifier and generation;
2. re-read and structurally compare the persisted envelope;
3. add or idempotently confirm the durable indeterminate record;
4. remove the operation from automatic outbox submission;
5. preserve its local projection or pending-deletion base;
6. preserve causal successors without making them submit-ready;
7. preserve existing conflicts and tombstones;
8. clear retry state for the operation;
9. mark the scope as requiring reconciliation without replacing the old generation or cursor.

Repeated delivery of the same indeterminate outcome must be harmless. A mismatched operation,
generation, reason, or current generation must fail without partially changing local state.

### Pull-side reconciliation requirement

When incremental pull returns `RECONCILIATION_REQUIRED`, atomically persist the reason and reported
current generation while retaining:

- the previous generation and cursor;
- the last-synchronized base;
- local records and tombstones;
- outbox operations and their existing generation bindings;
- indeterminate work and conflicts;
- retry history needed for audit-safe recovery behavior.

This is a terminal protocol state for G3, not a retryable outage. It consumes no automatic retry attempt,
does not schedule background retry, and exposes a privacy-safe `reconciliation-required` service status.
Manual retry must not rebind operations or clear this state. G4 will later start and commit full
reconciliation.

When a push produces an indeterminate outcome, stop recovery for the scope immediately. Do not submit all
remaining old-generation operations merely to convert them into additional indeterminate work.

### Browser acceptance tests

Cover at least:

- version-four migration preserving records, tombstones, cursor, pending operations, retry state, and
  synchronization lease data;
- initial pull establishing generation and cursor atomically;
- operation binding before first submission;
- unchanged envelope across timeout, retry, reload, manual recovery, and multi-tab ownership transfer;
- accepted result application with the generation envelope;
- atomic and idempotent movement to indeterminate work;
- rollback when indeterminate persistence fails;
- preservation of local projection, deletion base, causal successor, conflict, and available base;
- reconciliation-required pull without cursor advancement or generation replacement;
- no retry-budget consumption and no scheduled recovery for generation-terminal outcomes;
- reload exposing the same indeterminate and reconciliation-required status;
- fencing against a late response from a tab that lost the synchronization lease.

## Increment G3e: integrated validation and documentation

### Required scenarios

Validate these scenarios across the applicable contract, TypeScript, Java, IndexedDB, Spring, and
PostgreSQL boundaries:

1. **Current generation:** create, replace, delete, replay, and incremental pull continue to work through
   the production generation envelope.
2. **Retained retired receipt:** after rollover, an unchanged operation with retained evidence returns
   its original stable result.
3. **Lost acknowledgement and compacted receipt:** the original operation is not executed again and is
   preserved by the browser as durable indeterminate work.
4. **Expired incremental state:** the browser preserves its old boundary and local intent while recording
   reconciliation-required state.
5. **Migration and restart:** populated PostgreSQL and IndexedDB state survives upgrade, service restart,
   browser reload, and synchronization ownership transfer.

Run the current-generation path through the real browser-to-Spring-to-PostgreSQL topology. Exercise
actual rollover and compaction through PostgreSQL integration tests and the real browser transition
through IndexedDB/service tests. Do not ship a test-only administrative HTTP endpoint solely to make
Playwright trigger rollover. G5 owns the complete real-topology compaction and reconciliation matrix.

### Documentation updates

After implementation and validation:

- mark G3 validated in `foundation-implementation-plan.md` and record exact commands and dated evidence;
- update `synchronization-policy-traceability.md` for generation rollover, retained receipt replay, and
  indeterminate outcomes;
- update `docs/api/README.md` to describe the active generation envelope, bootstrap behavior, response
  union, cursor transition, and deliberate absence of G4 runtime adapters;
- update `docs/development/README.md` with any new focused or topology command;
- update the fixture capability manifest so implemented G3 behavior requires contract, TypeScript, and
  Java consumers;
- keep G4 and G5 work explicitly planned rather than implying complete reconciliation or production
  retention readiness;
- review all repository additions for English-language consistency under ADR-0018.

## Suggested commit and session sequence

The following sequence keeps each session independently reviewable:

1. **Define active G3 wire behavior**
   - amend ADR/API documentation;
   - update TypeSpec, generated artifacts, fixtures, and contract tests.
2. **Persist synchronization generations and rollover**
   - add the Flyway migration, cursor codec, server protocol types, rollover service, and PostgreSQL
     tests.
3. **Bind browser synchronization state to generations**
   - add the Dexie migration, generation bootstrap, base persistence, and immutable envelope binding.
4. **Preserve indeterminate operations durably**
   - add transport handling, atomic local transition, reconciliation-required state, statuses, and retry
     integration.
5. **Validate generation rollover and indeterminate recovery**
   - complete conformance, migration, restart, reload, multi-tab, and real-topology coverage; then update
     planning and traceability evidence.

At the end of each session, record:

- the commit or uncommitted files that form the handoff point;
- validation commands run and their results;
- remaining acceptance cases;
- any contract decision that changed from this plan;
- whether the next session may safely start the following increment.

## Full validation target

Before declaring G3 validated, run the independently attributable checks documented in
[`README.md`](README.md), including:

- contract formatting, generation drift, lint, schema, fixture, and conformance checks;
- web formatting, lint, architecture, strict type checking, unit tests, development and production
  builds, and browser tests;
- backend formatting, Checkstyle, PMD, unit tests, PostgreSQL integration tests, migration assertions,
  restart tests, and packaging;
- the applicable real synchronization topology;
- Compose validation and repository-wide whitespace, ignore, attribute, line-ending, documentation-link,
  architecture, privacy, and language reviews.

The final evidence must distinguish checks actually run from checks deferred to G5. G3 is complete only
when the lost-acknowledgement and receipt-compaction scenario cannot duplicate, discard, or silently
accept pending intent.
