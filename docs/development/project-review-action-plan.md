# Project review action plan

- Created: 2026-09-25.
- Status: proposed execution backlog; no fixes implemented by this document.
- Hortinis review baseline: `feat/d2-technical-sync-contracts` at `96be30a`.
- Hortinis Plants review baseline: `feat/wfo-grow-reconciliation` at `d5b418c`.
- This document is recorded on the reviewed Hortinis branch, `feat/d2-technical-sync-contracts`.
  Its findings refer to the review baselines above.

## Execution rules

1. Before starting a task, inspect the target repository's current branch and instructions. Confirm that
   the finding still applies; do not overwrite subsequent fixes or unrelated work.
2. Use the task IDs below in implementation notes and pull requests. Update each task's status and add
   commit references, validation commands and results when it is completed.
3. Use `planned`, `in progress`, `validated` or `blocked`. All tasks below start as `planned`.
4. A decision task produces an accepted specification or ADR before dependent implementation begins.
   This backlog proposes changes; it does not itself accept new product scope or technology choices.
5. Reuse the existing foundation and product increments. Amend their scope and acceptance criteria rather
   than creating a competing implementation plan or duplicating completed work.
6. Follow each repository's documented validation. Add behavioral regression tests for fixes; do not
   treat type checks, synthetic fixtures or documentation as evidence of a complete user workflow.
7. Keep E10 mandatory for V0 implementation and G6 mandatory for production business implementation.
   No task here authorizes real-user deployment before its access and recovery prerequisites exist.

Priority meanings:

- **P1:** correctness or data-preservation blocker before relying on the affected capability.
- **P2:** prerequisite for the relevant integration, V0 or field-trial milestone.
- **P3:** follow-up improvement that must not unnecessarily delay the first useful product test.

Size is relative scope, not a time estimate: **S** is a small focused change, **M** is a bounded capability,
and **L** needs multiple reviewable implementation changes.

## Task index

| ID | Task | Repository | Priority | Size | Direct dependencies |
| --- | --- | --- | --- | --- | --- |
| R01 | Group routine quality and documentation fixes | Both | P2 | S | None |
| R02 | Make server change ordering safe across commits | Hortinis | P1 | M | None |
| R03 | Preserve newer browser state on late acknowledgement | Hortinis | P1 | M | None |
| R04 | Coordinate synchronization across browser tabs | Hortinis | P1 | M | R03 |
| R05 | Complete browser/server deletion compatibility | Hortinis | P1 | L | R02, R03, R04 |
| R06 | Prevent concurrent catalog decisions from losing edits | Plants | P1 | M | None |
| R07 | Recover interrupted catalog publication | Plants | P2 | M | R06 |
| R08 | Make readiness reflect database availability | Hortinis | P2 | S | None |
| R09 | Strengthen shared behavioral conformance | Hortinis | P2 | M | R02, R03, R05 |
| R10 | Complete backend architecture enforcement | Hortinis | P2 | M | None |
| R11 | Add basic continuous integration | Both | P2 | M | R01 |
| R12 | Revise retry policy, then implement bounded recovery | Hortinis | P2 | M | Decision first; implementation after R04, R05 |
| R13 | Define access, ownership and local-data transitions | Hortinis | P2 | M | Existing P0.4 ownership specification |
| R14 | Revise product sequencing and define the first useful journey | Hortinis | P2 | M | None |
| R15 | Agree catalog consumer compatibility and import limits | Both | P2 | M | Existing P0.7a scope decision |
| R16 | Enforce separation of synthetic and advice-grade data | Plants | P2 | M | None |
| R17 | Curate the smallest evidence-backed validation subset | Plants | P2 | M | R16, accepted P0.7a |
| R18 | Build and hand off the local validation artifact | Both | P2 | L | R15, R17 |
| R19 | Validate the real integrated topology and publish E10 | Hortinis | P2 | L | R02–R05, R08–R10, all existing E10 prerequisites |
| R20 | Deliver the integrated V0 journey | Both | P2 | L | R14, R18, R19, existing V0 specification prerequisites |
| R21 | Add the safeguards required for a non-disposable field trial | Both | P2 | L | R13, R20, applicable implementation gates |
| R22 | Reduce catalog maintenance and resource risks | Plants | P3 | M | None; coordinate with active source-adapter work |

## Correctness and small fixes

### R01 — Group routine quality and documentation fixes

- Status: `planned`.
- Scope: small independent fixes, grouped into one change per repository.
- Hortinis: update the README and product handoff to describe the executable scaffold accurately;
  reconcile G1/E10 statements in synchronization traceability; correct C7's completion claim while
  architecture tests are missing; add Plants V1.4 to V0.1's direct prerequisites; align the tracker and
  dependency graph with the detailed plans.
- Plants: fix the 11 reported lint errors in `src/adapters/taxref/verify-pin.ts` without disabling safety
  rules. Format that file, `verify-pin-cli.ts`, `data/sources/taxref/archive-index.json` and its README.
- Acceptance: applicable lint and formatting checks pass; documentation links resolve; milestone status
  agrees with actual acceptance evidence. No unrelated dependency upgrades or policy changes.

### R02 — Make server change ordering safe across commits

- Status: `planned`.
- Problem: independent transactions can allocate journal sequences in one order and commit in another.
  A client can advance past an uncommitted lower sequence and never receive that change.
- Start in: `TechnicalRecordAcceptancePersistence`, `TechnicalRecordSynchronizationService` and the
  technical journal migrations under `services/sync`.
- Work: specify the publication invariant; implement a transaction-safe ordering mechanism per scope;
  document lock ordering and how the mechanism will support anchored snapshots. Update ADR-0010 where
  necessary. An ordinary identity sequence alone is insufficient.
- Acceptance: a controlled PostgreSQL test holds transaction A open while B attempts to publish a later
  change, pulls between commits, then proves neither change is skipped. Include rollback, deletion,
  pagination and independent record identities. Sequence gaps themselves need not be prohibited.

### R03 — Preserve newer browser state on late acknowledgement

- Status: `planned`.
- Problem: `commitAcceptedResult()` can replace a newer pulled revision with an older acknowledged
  value while leaving the incremental cursor advanced. This was reproduced in memory during review.
- Start in: `apps/web/src/app/persistence/technical-record-persistence.ts`.
- Work: make accepted-result persistence idempotent; prevent accepted revisions from regressing;
  preserve pending successors and local intent; validate that results correspond to the pending operation.
- Acceptance: pull revision 2, then apply the delayed acknowledgement for revision 1; revision 2 remains
  current. Also cover a newer local successor, repeated acknowledgement, rollback and database reopen.

### R04 — Coordinate synchronization across browser tabs

- Status: `planned`.
- Problem: service-instance flags do not serialize work across tabs sharing the same IndexedDB database.
- Work: select and document a bounded coordination mechanism for a synchronization scope, including
  ownership loss when a tab closes. Preserve transactional correctness even when a response arrives after
  coordination ownership changes. Keep local editing independent of synchronization ownership.
- Acceptance: two tabs can edit and recover against the same database without regressing projections,
  losing outbox work or treating duplicate acknowledgements as unrecoverable failures. Closing or
  suspending the coordinating tab does not permanently block recovery.

### R05 — Complete browser/server deletion compatibility

- Status: `planned`.
- Existing owner: G2c and the deletion portions of G2e.
- Problem: the reviewed server emits tombstones that the browser's live-record-only validator rejects.
  This is documented unfinished work, but it blocks integrated use once a deletion exists.
- Work: implement the result/change unions, retired-identifier errors, real Dexie migration, local
  deletion/outbox transaction, tombstone application and durable deletion conflicts. Until compatible,
  keep deletion unavailable in the integrated validation topology.
- Acceptance: create, replace and delete propagate through the real browser and server; repeated
  application is harmless; stale live changes cannot resurrect deleted identities; overlapping local
  work survives as an explicit conflict; interrupted page application cannot advance the cursor.

### R06 — Prevent concurrent catalog decisions from losing edits

- Status: `planned`.
- Start in: Plants `src/curation/grow-wfo-apply.ts`.
- Problem: two applications can read the same authoring dataset and publish complete replacements,
  allowing the later publication to discard the earlier accepted additions.
- Work: enforce exclusive mutation; check the expected dataset fingerprint under that protection;
  retain draft validation and deterministic IDs. Define an explicit stale-input outcome.
- Acceptance: concurrent independent decisions either preserve both results or reject one as stale
  without changing its predecessor's data. Concurrent reuse of an identity cannot overwrite content.

### R07 — Recover interrupted catalog publication

- Status: `planned`.
- Start in: Plants curation and importer directory-publication helpers.
- Problem: exception rollback around two renames does not cover process termination between them.
- Work: define publication/recovery states; retain recoverable staging or backup material; detect an
  interrupted publication on the next invocation; report whether a transaction actually committed.
  Apply the policy consistently to tracked authoring data and generated importer output.
- Acceptance: termination at each publication boundary leaves either the previous or complete new
  dataset recoverable. Recovery never deletes the only valid copy; reruns have a documented outcome.

### R08 — Make readiness reflect database availability

- Status: `planned`.
- Start in: `services/sync/src/main/resources/application.yaml` and Compose health checks.
- Work: include the essential PostgreSQL dependency in synchronization readiness; keep process liveness
  independent; preserve status-only responses and privacy-safe diagnostics.
- Acceptance: database loss after successful startup makes readiness fail; database recovery restores
  readiness; liveness remains healthy while the process can recover. Test beyond initial startup.

## Validation and architecture decisions

### R09 — Strengthen shared behavioral conformance

- Status: `planned`.
- Existing owner: E9 and G2e, with later generation/reconciliation cases owned by G3–G5.
- Work: introduce an explicit fixture/capability manifest; detect fixtures without required runtime
  consumers; distinguish shape checks from state-transition checks. Run applicable cases through actual
  parsers, rules and persistence boundaries, including expected errors and retained state.
- Acceptance: missing coverage for an implemented capability fails validation; create, replace, replay,
  conflicts and deletion agree across runtimes. Planned capabilities remain explicitly labelled, rather
  than silently skipped or reported as implemented.

### R10 — Complete backend architecture enforcement

- Status: `planned`.
- Existing owner: C7 and ADR-0002.
- Work: add the selected ArchUnit checks for pure-rule dependencies, package cycles and controller access
  to persistence. Add representative forbidden-dependency fixtures. Extract rules only where behavior
  warrants independent testing; retain one Spring application and lightweight internal boundaries.
- Acceptance: valid application dependencies pass; representative violations fail. C7 becomes validated
  only after its stated architecture criteria and documented checks pass.

### R11 — Add basic continuous integration

- Status: `planned`.
- Existing owners: Hortinis F4; Plants C1.4.
- Work: use pinned runtimes and frozen dependencies; execute each repository's relevant formatting,
  lint, types, contracts, tests and builds. Include database and browser checks where supported. Cache
  dependencies, not results that would conceal missing validation.
- Acceptance: a clean checkout is validated on pull requests. Plants CI uses fixtures and needs no large
  ignored archives. Neither workflow publishes releases, deploys services or downloads source catalogs.

### R12 — Revise retry policy, then implement bounded recovery

- Status: `planned`.
- Existing owners: ADR-0027, G2d and G2e.
- Decision: the four specified retry delays have upper bounds of 1, 2, 4 and 8 seconds, only 15 seconds
  of total scheduled waiting; the stated 30-second cap is never reached. Specify the intended outage
  coverage, request timeouts, exhaustion behavior, manual recovery and whether reconnect/focus events
  can initiate a new bounded cycle. Prevent reload from resetting an exhausted budget.
- Implementation: implement the accepted policy with durable state and injectable clock/scheduling
  boundaries after browser deletion and multi-tab behavior are reliable.
- Acceptance: deterministic tests cover the selected outage duration, offline periods, reload,
  exhaustion, manual retry and ownership transfer. No retry changes operation identity or loses intent.

### R13 — Define access, ownership and local-data transitions

- Status: `planned`.
- Existing owners: P0.5, P0.5a, ADR-0013 and the access portion of M4.1.
- Deliverable: an accepted specification for standalone use, individual-server access and shared-instance
  accounts; synchronization scope identity; device enrollment; session expiry; sign-out; revocation;
  server/account changes; and the treatment of local data and pending operations in each transition.
- Work: separate early access/ownership implementation from later bootstrap of the full garden model.
  Define enforcement that keeps the unauthenticated test-only scope out of production operation.
- Acceptance: isolation and transition scenarios are testable before production domain adapters are
  implemented. Do not infer a new authentication library or configuration default from this backlog.

### R14 — Revise product sequencing and define the first useful journey

- Status: `planned`.
- Existing owners: P0.2a, P0.3, P0.4a, P0.5a, P0.7a and the product tracker/dependency graph.
- Deliverables: an accepted minimum journey, revised dependencies and measurable quality criteria.
- Work: split M3.1 into early garden/space recording and later crop/lineage integration. Propose a V0
  memory scenario: record actual work or an observation, reload offline, correct it, retrieve it on a
  second device and inspect its history. Preserve the outbox and append-only history boundaries.
- Specify target phones/browsers, accessibility criteria, local-write and quick-entry targets, visible
  synchronization states, quota/migration failure behavior and the boundary of any basic recovery slice.
- Resolve two explicit decisions: gardener-facing language versus repository-language policy; and
  Angular Material adoption versus the unresolved component-library decision in ADR-0019. Record accepted
  outcomes before changing dependencies, translations or styling policy.
- Acceptance: plans and tracker agree; the journey tests garden memory as well as planning/advice;
  advanced lot lifecycle work is not an accidental prerequisite for a garden observation. E10/G6 gates
  remain intact; additional V0 scope is accepted before implementation.

## Catalog handoff and product integration

### R15 — Agree catalog consumer compatibility and import limits

- Status: `planned`.
- Existing owners: P0.7a, Plants V1.4, ADR-0014 and ADR-0020.
- Deliverable: a coordinated consumer contract covering supported schema versions, minimum consumer
  comparison, contract/fixture pins, retired identities and retained historical labels/evidence.
- Specify required versus optional artifacts, compressed and decompressed limits, record/count limits,
  interrupted staging, and atomic activation. Keep artifact-provided schemas separate from the consumer's
  trusted pinned validators. Distinguish V0 behavior from later HTTPS/update requirements.
- Acceptance: both repositories use the same version and fixture identifiers. Unsupported versions,
  corrupt/incomplete artifacts and exceeded limits cannot replace an active valid catalog.

### R16 — Enforce separation of synthetic and advice-grade data

- Status: `planned`.
- Problem: `data/validation/v1.2` contains synthetic evidence and accepted scenario reviews, including
  confidence values; those are software fixtures, not evidence of horticultural correctness.
- Work: define a machine-readable classification and profile gate. Keep fixtures useful for software
  validation while excluding them from advice-grade production projections and coverage claims.
- Acceptance: a negative fixture proves synthetic data cannot enter a production release. Validation
  reports distinguish structural correctness, editorial review, source rights and horticultural fitness.

### R17 — Curate the smallest evidence-backed validation subset

- Status: `planned`.
- Existing owner: Plants V1.2; exact scope comes from accepted Hortinis P0.7a.
- Work: curate the agreed generic plants and cultivar examples using verifiable evidence. Retain exact
  source/release/locator, rights, subject scope, geography, growing system and applicable timing. Record
  missing information or disagreement explicitly; do not convert qualitative sensitivity into invented
  numeric cold thresholds.
- Report coverage against the inputs required by each recommendation, not only imported record counts.
- Acceptance: at least the accepted V0 recommendation example has reviewed supporting evidence, plus
  limitation/abstention examples. Cultivar evidence never widens to the generic concept. The complete
  four-source curation program is not added as an implicit V0 prerequisite.

### R18 — Build and hand off the local validation artifact

- Status: `planned`.
- Existing owners: Plants V1.3/V1.4 and Hortinis V0.1 contract preparation.
- Work: validate authoring data; generate deterministic consumer JSONL.gz, manifest and attribution;
  provide the explicitly selected local file set. Pin trusted consumer schemas and small fixtures in
  Hortinis without importing Plants implementation code or committing generated full artifacts.
- Acceptance: two identical builds have identical output hashes; all references resolve; both
  repositories pass valid, corrupt-hash, incomplete, invalid-entry and unsupported-version cases.
  Profile enforcement excludes synthetic production data. Browser import waits for E10.

### R19 — Validate the real integrated topology and publish E10

- Status: `planned`.
- Existing owners: F3 and E10. The task index adds review regressions to the existing prerequisites;
  it does not replace the full E10 dependency list.
- Work: run browser, same-origin routing, Spring and PostgreSQL together. HTTP interception remains
  useful for focused failure injection but is not the sole synchronization evidence.
- Acceptance: real exchanges demonstrate local writes, server acceptance, retries, dependent ordering,
  pull, conflict retention, reload, offline operation and service recovery. Include the review regression
  cases and deletion behavior now exposed by the server. Publish commands, environment, results,
  limitations and deferred production behavior in the readiness report.

### R20 — Deliver the integrated V0 journey

- Status: `planned`.
- Existing owners: V0.1–V0.3, plus only the additional memory scope accepted by R14.
- Work: implement contract-first garden/selection/plan records, catalog acquisition and the approved
  recording/correction slice; connect them through real local outbox and server transactions; expose
  local, pending, synchronized, failed and conflict states; evaluate the reviewed recommendation example.
- Acceptance: two browser contexts independently acquire the catalog and retrieve synchronized records
  without duplication; missing catalog data preserves historical labels; offline reload retains work;
  recommendation output explains evidence and limitations. Record observed user-task results rather than
  equating a passing automated scenario with product usefulness.

### R21 — Add the safeguards required for a non-disposable field trial

- Status: `planned`.
- Existing owners: P0.6, the accepted access design, relevant M4 increments and deployment validation.
- Decision first: distinguish disposable developer V0 tests from trials retaining real garden history.
  If a limited trial precedes production readiness, explicitly accept its scope and prerequisites in the
  authoritative plans; this task does not waive G6.
- Work: implement the accepted access/isolation slice and a versioned basic export/restore path before
  collecting non-disposable history. Define pending-operation and server-reconnection semantics. Separate
  durable garden audit history from compactable synchronization receipts and journals.
- Acceptance: restore into a clean device preserves the trial's records, corrections, references and
  pending intent; server reconnection cannot silently duplicate effects. Test selected devices, storage
  failure, interrupted upgrades and loss of server access. Document operator recovery and trial limits.

## Non-blocking follow-up

### R22 — Reduce catalog maintenance and resource risks

- Status: `planned`.
- Work: separate dataset loading, semantic checks, source-lineage audit and publication in large curation
  modules as the active source track expands. Preserve existing behavior and fixture coverage; avoid a
  speculative plugin framework or a wholesale rewrite.
- Measure peak memory for pinned and scaled source inputs. CropGraph currently streams parsing but
  retains records for sorting: document that bound honestly. Add an external-sort or other bounded
  approach only when the measured input envelope requires it.
- Acceptance: source responsibilities are explicit, regression tests pass, and the supported resource
  envelope is recorded. This task does not block V1.3/V1.4 solely because broader refactoring is desirable.

## Suggested execution batches

1. **Small fixes:** R01 and R08, as separate reviewable changes. Start R11 once the baseline is green.
2. **Data preservation:** R02–R07. R02, R03 and R06 are independent starting points.
3. **Decisions and evidence:** R09–R15. Product and catalog specifications can progress while correctness
   work continues; dependencies still govern implementation.
4. **V0 prerequisites:** R16–R19. The small catalog artifact and E10 converge before V0 application work.
5. **Product validation:** R20, followed by R21 when real field use is intended and authorized by the gates.
6. **Maintenance:** R22 as justified by ongoing source work, without delaying the consumer handoff.

The complete production foundation, remaining MVP functionality, full backup lifecycle and production
deployment still follow their existing plans. Completion of this backlog's V0 tasks is not MVP readiness.

## Review evidence and limits

The review ran TypeScript checks for both repositories, frontend architecture checks and their tests,
frontend/contract formatting, local contract validation, catalog lint/format checks, 49 catalog schemas,
124 positive/negative catalog cases and semantic validation of the small dataset. In-memory probes
reproduced the delayed-acknowledgement regression and browser rejection of a server tombstone fixture.

The server ordering and catalog publication findings came from code analysis; their task acceptance
criteria require deterministic reproductions. Full Gradle, Vitest, Playwright, container and deployment
validation were not rerun during the review. Preserve that distinction in future completion claims.
