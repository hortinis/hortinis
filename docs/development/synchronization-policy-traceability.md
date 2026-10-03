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
| Transient failure uses bounded retry without hiding permanent failure | ADR-0010, ADR-0027, and ADR-0028 | Durable retry-state and fenced-lease rules; operation and cursor contracts remain stable across attempts | G2 validated retry, durable dispatch reservation, exhaustion, manual recovery, timeout, and stale-owner rejection; G5 repeats across retention |
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
