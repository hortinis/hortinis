# G2e review hardening plan

- Status: proposed
- Baseline: `feat/g2e-validation` (validated G2e)
- Source: external code and documentation review of the full branch (reading-based; nothing below has
  been executed, so every "verify first" item must be confirmed before work starts)

Increment identifiers `H0`-`H9` are provisional; rename them to fit the product tracker. Every increment
follows the repository rules: English only (ADR-0018), an ADR before any technology or policy choice,
tests with each behavior, documentation updated with each public-contract change, and no new
dependency without a decision record.

## 1. Findings and owners

| # | Finding | Increment |
| --- | --- | --- |
| F1 | Transient database failures return `500`, which the browser never retries (ADR-0027 intent broken) | H1 |
| F2 | `value` is unbounded; U+0000 (and possibly lone surrogates) cannot be stored and surface as `500` | H2 |
| F3 | A permanently rejected (`400`) head operation appears to block the whole outbox | H2, H6 |
| F4 | `500` responses are not logged; sync routes are logged as `/unknown` | H3 |
| F5 | Server uses stringly-typed identifiers and revisions, `instanceof` chains, and duplicated validation | H4 |
| F6 | Browser sync service and persistence are oversized, flag-driven, and hard to reason about | H5, H6 |
| F7 | Outbox supports one dependent successor per record; a third offline edit throws | H6 |
| F8 | Idle tabs never pull; other devices' changes need a reload or local write | H8 |
| F9 | No request for persistent browser storage (ADR-0008) | H7 |
| F10 | Compose password persists in the volume; bind-mount permissions; e2e test title and helpers | H9 |
| F11 | Stale README statements; broken cross-repository link; ADR `Date` missing on most ADRs; manual link checks | H0 |
| F12 | Open policy questions (problem details, request parsing, Web Locks, `navigator.onLine`) | Section 4 |

## 2. Order and dependencies

```text
H0 (docs, tooling) ---------------------------------------------+
H1 + H2 + H3 (one contract regeneration) --> H4 (no wire change)  |
H9 (small, independent) -----------------------------------------+--> before G2f
H5 (no behavior change) --> H6 --> H7 ... G3 ... --> H8 (after G3)
```

- Land H0-H4 before G2f so scope partitioning builds on the hardened contract.
- Land H5 before G3: G3 adds the generation envelope to the same service, and refactoring afterwards
  doubles the risk.
- Land H8 after G3 so refresh triggers are written once against the generation-aware pull.

## 3. Increments

### H0. Documentation corrections and automated documentation checks

- Status: `implemented`; validated alongside H1 (2026-10-03). Effort: small. Depends on: none.
- Apply `g2e-review-fixes.patch`: root and web README corrections, development README (Gradle in Compose,
  Docker requirement, volume password note), integration-test title, sync route names in request logs.
- Replace the broken `../../../hortinis-plants/...` link in `v0-dependency-graph.md` with the repository
  URL, because a sibling-checkout path cannot resolve on GitHub.
- Add the missing `Date` line to 27 of 29 ADRs, using the first commit date of each file
  (`git log --diff-filter=A --format=%as -- <file>`).
- Add `scripts/docs/check-docs.mjs` (Node built-ins only) with `node:test` fixtures, exposed as
  `pnpm docs:validate` and called from `scripts/validate.sh`. It checks: relative links and heading
  anchors; ADR `Status` and `Date` headers; every ADR file listed in `docs/architecture/README.md`; an
  explicit allowlist for cross-repository URLs. Replace the manual link-check bullet in
  `docs/development/README.md` with the command.
- Amend ADR-0027 with one sentence: `navigator.onLine` is a hint only; request failure remains the
  authoritative offline signal.
- Acceptance: `pnpm docs:validate` fails on a deliberately broken fixture and passes on the repository;
  no stale statement remains about an "empty" scaffold.

### H1. Transient failure mapping (`503`)

- Status: `implemented`; validated (2026-10-03).
  Effort: small-medium. Depends on: none.
- Map Spring `TransientDataAccessException` and `DataAccessResourceFailureException` (connection loss,
  pool exhaustion, query timeout, deadlock, serialization failure) to `503` with `Retry-After: 1`.
  Unexpected non-transient exceptions stay `500` and are deliberately not retried.
- Extend the existing access-service `503` TypeSpec response with distinct synchronization
  unavailability, regenerate OpenAPI and schemas, and document it in
  `docs/api/README.md` and ADR-0010 (the browser already treats 502/503/504 as unavailable).
- Tests: PostgreSQL integration tests with a forced connection failure and a forced lock failure expect
  `503`, no partial writes, and a successful replay of the same operation afterwards; a browser
  transport test confirms `503` classification, and real HTTP recovery tests confirm one reserved
  attempt per dispatch, exhaustion, and manual recovery; add a shared fixture for the response.
- Acceptance: stopping PostgreSQL during `pnpm test:e2e:topology` recovers through bounded retry after restart.

Implementation also covers recognized JDBC availability causes wrapped by transaction-start failure,
and translates PostgreSQL lock timeout `55P03` in the JDBC adapter. The existing access-service `503`
contract is preserved alongside the new `SYNCHRONIZATION_UNAVAILABLE` body; generated schemas and
the shared fixture distinguish them. Persistence failures always send `Retry-After: 1` and
`Cache-Control: no-store`. Browser scheduling retains ADR-0027's full jitter and bounded budget;
the header does not change that policy. The isolated topology uses a test-only one-second pool timeout.
Validation evidence is recorded in the foundation implementation plan.

### H2. Input bounds and unsendable operations

- Status: `implemented`; validated (2026-10-04). Effort: medium. Decision D1 is recorded in ADR-0031.
- Contract: add `@maxLength` for `value` (value from D1) and exclude U+0000 with a pattern; regenerate.
- Server: enforce the same limit and NUL rule in the parser (`400 INVALID_REQUEST`), and bound request
  size with Jackson stream-read constraints or a body-size filter (verify the Spring Boot 4 property
  names). Probe lone surrogates against PostgreSQL; reject them too if they fail.
- Browser: apply the same guard at the local commit boundary (`commitCreate`, `commitReplace`), so an
  operation the server can never accept is rejected before it enters the outbox.
- Verify first (F3): confirm with a test whether a `400` on the head operation blocks later operations
  forever. If confirmed, H6 adds a quarantine path; until then the commit-boundary guard prevents the case.
- Tests: shared fixtures `invalid-value-nul.json` and `invalid-value-too-long.json` run by both runtimes;
  a regression test that previously produced `500`.
- Acceptance: invalid input produces a controlled rejection without partial writes; valid bounded values round-trip unchanged. Unexpected infrastructure failures may still return `500`.

Implementation uses a shared TypeSpec scalar and equivalent pure runtime rules: at most 4,096 Unicode
code points, no NUL, and no unpaired surrogates. The JDBC probe confirmed that lone surrogates silently
become `?`; H2 therefore rejects them before persistence. A bounded servlet filter enforces the complete
65,536-byte body ceiling, including whitespace and chunked input; Boot 4.1.1 Jackson read constraints
bound nesting and token count. Both local commit boundaries reject invalid values before writes.

F3 is reproduced across recovery and database reopening for permanent HTTP `400` and legacy invalid
request-boundary failures. The head stays retained and blocks later work; H6 still owns quarantine.
H2 does not claim to repair existing invalid outboxes. Existing oversized accepted history also requires
the compatibility gate in [ADR-0031](../architecture/decisions/0031-technical-record-input-bounds.md).
Validation evidence is recorded in the [foundation implementation plan](foundation-implementation-plan.md#h2-review-hardening-evidence--2026-10-04).

### H3. Observability corrections

- Status: `implemented`; validated (2026-10-05). Effort: small. Depends on: none.
- Log every `500` once as `event=request_failed` with the exception class name and the existing
  `request_id`. Never log the exception message: database errors can embed record values.
- Keep the route-template fix from H0 and add `/api/v1/sync/operations` and `/changes` assertions.
- Tests: a privacy test submits a sentinel value that a forced database error would echo and asserts the
  sentinel never appears in captured logs; a route test asserts both sync routes are named.
- Acceptance: ADR-0015 privacy rules are covered by an automated test for the failure path.

The common MVC `500` response boundary emits one ERROR-level `request_failed` with `exception_class`
and the existing random request/trace IDs. Non-transient transaction-start fallbacks pass the original
exception to that same boundary. Messages, throwable payloads, SQL, and persisted identifiers are never
attached; the empty `500` response and existing `503` classification stay unchanged. H0's route
allowlist and assertions are retained. Parameterized tests cover both routes and every existing
permanent-failure case, correlation, MDC cleanup, and controlled responses without failure events.
A real HTTP/PostgreSQL trigger echoes the submitted value in a non-transient error; captured output
excludes the value and operation/record IDs, acceptance rolls back, and an unchanged retry succeeds
after removing the trigger. Failures outside MVC remain a separate logging-layer verification scope.
See the [validation evidence](foundation-implementation-plan.md#h3-review-hardening-evidence--2026-10-05).

### H4. Server domain cleanup (no wire change)

- Status: `implemented`; validated (2026-10-06). Effort: medium. Depends on: H1-H3.
- Introduce pure `RecordId`, `OperationId`, and accepted `Revision(long)` value types with boundary
  parsing/formatting and exact `next()` arithmetic. JDBC conversions remain in persistence; all wire
  revisions remain decimal strings.
- Preserve unbounded positive-decimal request expectations with `ExpectedRevision(BigInteger)`;
  narrowing them to `long` would change existing `404`/`409` behavior. The rationale and HTTP mapping
  boundary are recorded in ADR-0009.
- Replace operation `instanceof` chains with exhaustive pattern switches over the sealed
  `TechnicalRecordOperation`. Persistence reconstructs typed receipt operations; replay uses record
  equality. No implicit stored-kind fallback remains.
- Validate operations in compact constructors and delete `OperationRules`. The parser keeps exact
  JSON shape checks and maps construction failures locally to `400 INVALID_REQUEST`; unexpected
  programming failures retain H3's privacy-safe `500` boundary.
- Compute the accepted revision once in the service and pass it to persistence. Preserve record and
  scope lock ordering, transactional acceptance, retained deletion, replay, and pagination.
- Add one composed annotation for the four repeated persistence conditions, retaining their default
  activation. Replace inline fully qualified names with imports; move Jackson result/change metadata
  to explicit HTTP wire representations.
- Tests: constructor invariants, UUID syntax and redaction, canonical revision syntax, exact overflow,
  unbounded expectations, structural equality, parser failure translation, and PostgreSQL rollback.
  Existing fixture/HTTP expectations remain unchanged; Java helpers follow the new typed API.
- Acceptance: unit, integration, conformance, quality, repository, and topology checks pass; no string
  revision arithmetic remains in the service, and contract artifacts and migrations are unchanged.

Validation evidence is recorded in the
[foundation implementation plan](foundation-implementation-plan.md#h4-review-hardening-evidence--2026-10-06).

### H5. Browser synchronization service decomposition (no behavior change)

- Status: `implemented`; validated (2026-10-08). Effort: large. Depends on: none (schedule before G3).
- Step 1, safety net: add characterization tests that record the exact `status` signal sequence and
  outcomes for the main scenarios (success, offline, retry, exhaustion, manual retry, two-tab contention).
  The existing retry and coordination specs stay green throughout.
- Step 2, extract in small commits, keeping `TechnicalRecordSynchronizationService` as a facade so callers
  and specs do not change:
  - `RetryPolicy`: pure function from attempt count and jitter to "retry after N ms" or "exhausted",
    holding the ADR-0027 constants;
  - a promise-based single-flight/mutex replacing the four boolean flags and the busy-wait loops;
  - `SynchronizationStatusStore`: owns the signal and permits only legal transitions, removing the
    scattered `if (!recoveryInProgress) status.set(...)` guards;
  - push loop and pull loop as separate collaborators;
  - triggers (online event, retry timer, coordination retry) separate from the loops.
- Step 3, small fixes: return `busy` (not `empty`) when a push or pull is already running; narrow
  `failureReason` so an arbitrary `Error` is not labeled `local-persistence`.
- Guardrails: add ESLint `max-lines` and `complexity` limits for `src/app/sync` and
  `src/app/persistence` so the files cannot regrow.
- Acceptance: method signatures, status signal, existing type import paths, and persisted data unchanged;
  the two Step 3 behavior corrections are explicit exceptions; the facade is under about 150 lines; each
  collaborator has direct unit tests; `pnpm validate` and `pnpm test:e2e:topology` pass.

Implementation keeps the facade at 147 total lines and constructs concrete collaborators per facade
instance. Recovery calls owned exchanges directly; public push/pull cannot reuse active ownership.
The promise gate waits for setup/release without spinning and rejects standalone contention. Explicit
reload recovery and manual retry wait for standalone exchange; active recovery rejects duplicate
recovery. Existing request counters preserve edits arriving during final pull and release. The status
store enforces publication boundaries. Angular destruction permanently disables triggers and queued
recovery, including late failures and callbacks already queued by the runtime.

The safety net passed against the original service before extraction. Direct tests cover policy,
execution, triggers, status, retry settlement, request reservation, push/pull commits, and recovery.
Guardrails enforce 350 nonblank/noncomment lines and complexity 15, plus 150 lines for the facade.
Measured file-specific exceptions retain the current wire-validator complexity (25) and persistence
size/complexity (606/38); H6 removes the persistence exception when splitting it. No schema, generated
contract, dependency, retry policy, or business workflow changes.

Full repository validation, cross-runtime conformance, and both real topology scenarios passed.
See the [validation evidence](foundation-implementation-plan.md#h5-review-hardening-evidence--2026-10-08).

### H6. Outbox chains, coalescing, and persistence split

- Status: `implemented`. Effort: large. Depends on: H5. D5 is resolved by
  [ADR-0032](../architecture/decisions/0032-outbox-chains-coalescing-and-quarantine.md).
- Split `technical-record-persistence.ts` by concern (outbox commits, accepted results, pulled pages)
  with a shared transaction helper. Return values from Dexie transaction callbacks instead of
  `let x!` plus non-null assertions.
- Generalize successors from one to N: each dependent operation keeps `predecessorOperationId` and has its
  `expectedRevision` resolved from the predecessor's accepted result at submit time.
- Coalesce only operations never submitted. Persist a `submittedAt` marker before the first send and never
  mutate a marked operation, because a lost-acknowledgement replay compares the operation structurally
  and a changed body returns `OPERATION_ID_REUSED`. Proposed rules for unsent operations: create+replace
  becomes create with the latest value; replace+replace becomes one replace; replace+delete becomes
  delete; create+delete drops both.
- Quarantine (F3): a permanently rejected operation moves to a `rejectedOperations` table with its
  category, no record value in diagnostics, and a visible status, so it cannot block later work.
- Dexie version 6 migration (version 5 already implements ADR-0030 accepted-state repair) with a populated-fixture test (extend `migration-fixture-database.ts`).
- Tests: deterministic model-based sequences using the existing harness (no new dependency without an
  ADR), lost-acknowledgement replay of a marked operation, three-edit offline scenario end to end.
- Acceptance: any number of offline edits to one record syncs in order, or collapses by the rules, and
  never produces `OPERATION_ID_REUSED`.

Implementation uses a small persistence facade, explicit-table transaction scaffolding, and concrete
collaborators. Preparation reserves the bounded attempt and marks submission atomically, excluding
local metadata from every wire operation. Legacy ready envelopes remain immutable through an unknown-
submission marker. Dependency revisions resolve at preparation. Recognized rejections retain local
intent in quarantine; descendants remain blocked, independent work continues, and a separate observable
rejection summary survives aggregate completion. No new dependency or HTTP contract is introduced.

Validation evidence is recorded in the [foundation implementation plan](foundation-implementation-plan.md#h6-review-hardening-evidence--2026-10-08).

### H7. Persistent storage request

- Status: `implemented`; validated (2026-10-09). Effort: small. Depends on: none (ADR-0008 already requires it).
- Add a `StoragePersistence` service that calls `navigator.storage.persist()` after the first local
  commit and exposes a status signal (`persistent`, `best-effort`, `unsupported`). Feature-detect and
  never fail a local write because of it.
- Tests with a faked `navigator.storage`; document per-browser behavior in ADR-0008.
- Acceptance: the status is observable; a non-persistent store with a non-empty outbox is flagged.

Implementation checks browser persistence at startup without prompting and requests at most once per
application session after a successful local commit. API failures never delay writes or synchronization.
Readonly permission status and live outbox observation drive shell warnings across reloads and tabs;
failed observations remain distinct from an empty outbox. No dependency, schema, or wire change.
See [ADR-0008](../architecture/decisions/0008-web-and-local-persistence.md#browser-persistence-lifecycle-h7)
and the [validation evidence](foundation-implementation-plan.md#h7-review-hardening-evidence--2026-10-09).

The [aborted-read review follow-up](foundation-implementation-plan.md#h7-aborted-read-review-follow-up--2026-10-10)
handles failures inside the outbox live query so Dexie cannot silently suppress unavailable status.
Initial and subsequent active-transaction abort regressions verify refresh after later writes.

### H8. Foreground refresh triggers

- Status: `planned`. Effort: medium. Depends on: G3, decision D4, a new ADR amending ADR-0027's
  "focus does not start recovery" rule.
- Add a pull-only refresh distinct from recovery: on `visibilitychange` to visible, and on a jittered
  interval while visible, online, idle, and not exhausted, with a minimum spacing. It uses the existing
  lease, never clears exhaustion, and its failures do not create retry records or consume the push
  budget; they back off to the maximum interval instead.
- Tests: fake clock unit tests; a Playwright scenario using `page.clock` in which a second browser
  context sees the first context's change without reload.
- Acceptance: a change from another context appears within one refresh interval; background refresh
  never changes ADR-0027 retry counters.

### H9. Compose and end-to-end test cleanup

- Status: `planned`. Effort: small. Depends on: none.
- Compose: make the container user configurable (for example `HORTINIS_UID`/`HORTINIS_GID`) and document
  bind-mount permission behavior. Keep this minimal: production images (F2) replace the bind mount.
- E2E: extract the repeated IndexedDB boilerplate in `integrated-sync.spec.ts` into one helper under
  `e2e/support/`, and assert through `GET /api/v1/sync/changes` that the retried create produced exactly
  one journal change.
- Acceptance: the integrated scenario is unchanged in coverage and about one third shorter.

## 4. Decisions needed

| ID | Question | Recommendation |
| --- | --- | --- |
| D1 | Maximum `value` length for the technical record | Resolved by ADR-0031: 4,096 Unicode code points, no NUL or unpaired surrogates |
| D2 | Adopt RFC 9457 `application/problem+json` for errors | Defer; record "not now" in an ADR and decide before the first public release |
| D3 | Keep hand-written `JsonNode` parsing or move to records plus validation | Keep; add table-driven parser tests; revisit at about five operation kinds |
| D4 | Refresh policy: interval, spacing, and whether to add server-sent events later | Poll first (about 30-60 s while visible); defer server push |
| D5 | Coalescing rules and the "never mutate a submitted operation" invariant | Resolved by ADR-0032, including conservative legacy migration and atomic submission preparation |
| D6 | Web Locks versus the IndexedDB lease | Keep the lease (ADR-0028); correctness rests on idempotency and the cursor compare-and-set |

## 5. Definition of done for every increment

- `pnpm validate`, `pnpm conformance:validate`, `pnpm docs:validate` (after H0), the backend
  `integrationTest`, Spotless, Checkstyle, and PMD tasks, and `pnpm test:e2e:topology` pass.
- Contract changes regenerate artifacts and update fixtures for both runtimes in the same change.
- `docs/development/foundation-implementation-plan.md`, `synchronization-policy-traceability.md`, and
  `project-review-action-plan.md` record status and validation evidence with the date.

## 6. Risks

- H5 touches concurrency behavior; mitigate with the characterization tests and small commits.
- H6 can corrupt idempotent replay if a submitted operation is ever mutated; the `submittedAt` invariant
  and its dedicated test are mandatory.
- H1/H2 change the public contract. H1 has already landed; H2 regenerates independently and keeps both runtime fixtures aligned.
- Effort figures are rough relative sizes, not estimates in days.

## 7. H0 implementation status

H0 is implemented as of 2026-10-03. Validation evidence and the remaining environment limitations are
recorded in the [foundation implementation plan](foundation-implementation-plan.md#h0-review-hardening-evidence--2026-10-03).
