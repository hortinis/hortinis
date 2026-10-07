# Production synchronization policy traceability

This document maps the production guarantees selected by G1 to their wire evidence and implementation
owners. It distinguishes accepted policy from behavior already demonstrated by the V0 walking skeleton.
G1 is validated; E10 remains a separate V0 readiness-reporting prerequisite.

| Required guarantee or failure case | Accepted decision | Contract or fixture evidence | Implementation and final validation |
| --- | --- | --- | --- |
| Incremental history can be compacted without expiring local intent | ADR-0023 | `ReconciliationRequiredError`; `reconciliation-required.json` | G3; G5 |
| A lost acknowledgement replays safely while its receipt exists | ADR-0010 and ADR-0023 | Existing `OperationResult`; `replay-equivalent.json` | E4 and G2e validated stable replay; G5 repeats across retention |
| A lost acknowledgement whose receipt is gone is not submitted as new | ADR-0023 and ADR-0026 | `IndeterminateOperationOutcome`; `indeterminate-operation.json` | G3; G5 |
| Deletion propagates and stale clients cannot resurrect an identity | ADR-0024 | `DeleteTechnicalRecordOperation`, tombstone result and change variants, `TechnicalTombstone`, and `RecordIdentifierRetiredError`; deletion and snapshot fixtures | G2 validated end to end; G5 repeats across retention |
| Interrupted incremental exchange can resume without advancing past unapplied work | ADR-0010 and ADR-0023 | Existing opaque `SyncCursor` and tombstone-capable `ChangePage` | G2 validated rollback, restart, reload, and real topology; G5 repeats after compaction |
| Transient failure uses bounded retry without hiding permanent failure | ADR-0010, ADR-0027, and ADR-0028 | `SynchronizationUnavailableError`; `synchronization-unavailable.json`; durable retry-state and fenced-lease rules | H1 adds HTTP 503 mapping, PostgreSQL rollback/replay and real HTTP retry coverage; G2 validated durable dispatch reservation, exhaustion, manual recovery and stale-owner rejection; G5 repeats across retention |
| A delayed acknowledgement preserves the newer accepted value and pending intent | ADR-0030 | Existing record, operation, and cursor contracts | Separate accepted-state persistence, successor and rollback regressions, resumable G2 legacy repair |
| Local edits made during exchange remain eligible for recovery | ADR-0027 and ADR-0030 | Existing outbox operation contracts | Final-pull and lease-release recovery draining; conflicts do not cause a busy loop |
| Paginated snapshot pages all represent one server point in time | ADR-0025 | `ReconciliationSession` and `SnapshotPage`; snapshot fixtures | G4; G5 |
| Writes accepted during a snapshot are not missed | ADR-0025 | Final-page `incrementalCursor` strictly after `anchorSequence` | G4 PostgreSQL and browser tests; G5 end to end |
| Expired snapshot continuation does not erase progress or intent | ADR-0025 | `SnapshotExpiredError` | G4; G5 restart and repetition tests |
| Full reconciliation preserves non-overlapping local changes | ADR-0026 | `ReconciliationOutcome`; `reconciliation-outcomes.json` | G4; G5 |
| Concurrent work remains explicit without domain last-write-wins | ADR-0010 and ADR-0026 | `conflict` and `keep_indeterminate` reconciliation outcomes | G4 generic behavior; later domain workflow for resolution |

## Deliberate transition state

The generated OpenAPI document exposes the anchored reconciliation-session endpoints before their
adapters exist. The other production models are emitted as standalone JSON Schemas because they become
inputs to the existing operation and pull boundaries during G2 and G3. Until those tasks are complete,
only the two V0 push and pull adapters described in the API documentation are runnable.

Cross-runtime conformance covers the V0 corpus and G2 deletion fixtures through the explicit capability
manifest. Contract validation fails when a fixture is unlisted or an implemented behavior lacks its
TypeScript or Java consumer. G2e also runs deletion and interrupted acknowledgement recovery through the
real browser, service, and PostgreSQL topology. G3 and G4 add the remaining production-policy behavior;
G5 repeats the complete shared corpus across compaction and reconciliation boundaries.

## H0 review hardening — 2026-10-03

The [G2e review hardening plan](g2e-review-hardening-plan.md) H0 increment is implemented.
Documentation checks, ADR dates, development guidance, and privacy-safe synchronization route naming
are updated; no wire contract changed. The [validation evidence](foundation-implementation-plan.md#h0-review-hardening-evidence--2026-10-03)
records passing documentation/frontend checks and the backend build using temporary output directories.
Initial full validation was pending because the default backend output had ownership conflicts and the
G2e topology database password was not configured. These checks subsequently passed alongside H1.

## H1 review hardening — 2026-10-03

The [H1 validation evidence](foundation-implementation-plan.md#h1-review-hardening-evidence--2026-10-03)
records passing repository, contract, cross-runtime, PostgreSQL, browser, and topology checks.
`SYNCHRONIZATION_UNAVAILABLE` is distinct from access failure and carries fixed, privacy-safe text.
Real lock/connection failures roll back acceptance writes; unchanged submission and stable replay
produce one journal entry. Actual HTTP `503` consumes one reserved browser attempt, preserves the
operation or cursor, and supports bounded exhaustion and explicit manual recovery. Unexpected `500`
does not schedule retry. `Retry-After: 1` is published without changing ADR-0027's full-jitter policy.

## H2 review hardening — 2026-10-04

The [H2 hardening increment](g2e-review-hardening-plan.md#h2-input-bounds-and-unsendable-operations)
implements ADR-0031's 4,096-code-point value rule and complete request-body ceiling across TypeSpec,
HTTP parsing, pure runtime rules, and local commits. Shared fixtures cover NUL, length, malformed
surrogates, and a supplementary-character boundary. PostgreSQL and real HTTP tests cover unchanged
storage/replay, controlled rejection without partial writes, and privacy-safe diagnostics.
F3 is confirmed for HTTP and request-boundary rejections across recovery and reopening; H6 still owns
quarantine. Existing oversized history requires the documented compatibility audit before rollout.
The [validation evidence](foundation-implementation-plan.md#h2-review-hardening-evidence--2026-10-04)
records passing full repository, conformance, backend quality, PostgreSQL, and topology validation.

## H3 review hardening — 2026-10-05

The [H3 hardening increment](g2e-review-hardening-plan.md#h3-observability-corrections) adds one
privacy-safe failure event at the synchronization MVC `500` boundary, including non-transient
transaction-start failures. The event records only the handled exception class and existing random
request/trace correlation IDs alongside fixed logging metadata. H0's synchronization route allowlist
and assertions remain in place. Tests cover both routes, controlled responses without failure events,
MDC cleanup, and a real PostgreSQL error that echoes record content without leaking it to logs.
No wire contract, dependency, schema, or retry behavior changes. Failures outside MVC remain a separate
logging-layer verification scope. Full repository validation, cross-runtime conformance, backend
quality checks, and both real topology scenarios passed. See the
[validation evidence](foundation-implementation-plan.md#h3-review-hardening-evidence--2026-10-05).

## H4 review hardening — 2026-10-06

The [H4 hardening increment](g2e-review-hardening-plan.md#h4-server-domain-cleanup-no-wire-change)
introduces typed record/operation identities, exact accepted revisions, constructor validation,
exhaustive operation dispatch, and structural replay equality. Unbounded request expectations retain
existing error classification; JDBC conversions and typed receipt reconstruction stay in persistence,
and explicit HTTP wire mapping preserves public strings. Transaction and publication ordering,
deletions, replay, and pagination remain unchanged. No contract, dependency, or migration changes.
Full repository validation, cross-runtime conformance, backend quality checks, Compose validation,
and both real topology scenarios passed. Backend coverage includes 67 unit/conformance tests and
42 PostgreSQL integration tests. See the
[validation evidence](foundation-implementation-plan.md#h4-review-hardening-evidence--2026-10-06).
