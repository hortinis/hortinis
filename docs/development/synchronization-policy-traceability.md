# Production synchronization policy traceability

This document maps the production guarantees selected by G1 to their wire evidence and implementation
owners. It distinguishes accepted policy from behavior already demonstrated by the V0 walking skeleton.
G1 remains specified rather than validated until E10 supplies the V0 readiness evidence required by the
foundation plan.

| Required guarantee or failure case | Accepted decision | Contract or fixture evidence | Implementation and final validation |
| --- | --- | --- | --- |
| Incremental history can be compacted without expiring local intent | ADR-0023 | `ReconciliationRequiredError`; `reconciliation-required.json` | G3; G5 |
| A lost acknowledgement replays safely while its receipt exists | ADR-0010 and ADR-0023 | Existing `OperationResult`; `replay-equivalent.json` | E4 demonstrated V0; G5 repeats across retention |
| A lost acknowledgement whose receipt is gone is not submitted as new | ADR-0023 and ADR-0026 | `IndeterminateOperationOutcome`; `indeterminate-operation.json` | G3; G5 |
| Deletion propagates and stale clients cannot resurrect an identity | ADR-0024 | `DeleteTechnicalRecordOperation`, tombstone result and change variants, `TechnicalTombstone`, and `RecordIdentifierRetiredError`; deletion and snapshot fixtures | G2a through G2c; G2e; G5 |
| Interrupted incremental exchange can resume without advancing past unapplied work | ADR-0010 and ADR-0023 | Existing opaque `SyncCursor` and tombstone-capable `ChangePage` | G2c; G2e; G5 |
| Transient failure uses bounded retry without hiding permanent failure | ADR-0010 and ADR-0027 | Durable retry-state rules; operation and cursor contracts remain stable across attempts | G2d; G2e; G5 |
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

Cross-runtime conformance currently covers the V0 corpus. Contract tests validate the G1 and G2a policy
fixtures against generated schemas. G2b through G4 add TypeScript and Java consumers with each behavior; G5 runs
the complete shared corpus through browser, service, PostgreSQL, restart, migration, and compaction paths.
