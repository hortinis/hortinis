# Hortinis product tracker

- Status: validated for P0.1; product implementation has not started.
- Updated on: 2026-09-11.
- Authority: [functional decisions and MVP scope](../product/functional-decisions.md), [domain model](../product/domain-model.md), and [product implementation plan](product-implementation-plan.md).
- Purpose: provide explicit traceability from accepted MVP decisions and domain acceptance scenarios to reviewable implementation increments.

## 1. Status model

| Status | Meaning |
| --- | --- |
| `planned` | The increment is defined but its specification, implementation, or validation evidence is incomplete. |
| `in progress` | Work has started and is not yet accepted. |
| `validated` | The stated acceptance criteria passed and evidence is recorded. |
| `deferred` | Explicitly outside the current MVP or V0 scope. |

All product increments other than the P0.1 tracking deliverable are currently `planned`. A product increment cannot be called validated from a design document alone.

## 2. Increment register

The detailed scope, dependencies, technical work, exclusions, and acceptance criteria remain in the [product implementation plan](product-implementation-plan.md). This register is the traceability index for that plan.

| Increment | Purpose | Direct prerequisites | Status | Evidence required |
| --- | --- | --- | --- | --- |
| P0.1 | Establish this tracker and traceability model | None | `validated` | This document; repository documentation checks |
| P0.2a | Define the first-test journey and visible V0 states | P0.1 | `planned` | Reviewed journey and state scenarios |
| P0.4a | Define V0 garden, plant-selection, and cultivation-plan contracts | P0.1 | `planned` | Contract and invariant specification |
| P0.5a | Define the constrained V0 synchronization scope | P0.4a | `planned` | Operation mapping, ordering, conflict, and test-profile specification |
| P0.7a | Define the local catalog-validation contract | P0.1 | `planned` | Artifact profile, fixtures, provenance, and rule examples |
| V0.1 | Acquire and validate a local catalog artifact | Foundation E10, P0.7a, `hortinis-plants` V1.4 | `planned` | Import, failure-path, offline-reload, and browser evidence |
| V0.2 | Create and synchronize the first business records | Foundation E10, P0.2a, P0.4a, P0.5a | `planned` | Two-browser, offline, reload, retry, and conflict evidence |
| V0.3 | Run the integrated first product test | V0.1, V0.2 | `planned` | Complete journey evidence and reviewed recommendation result |
| M1–M6 | Deliver the production MVP after V0 and foundation G6 | V0.3, foundation G6, increment-specific prerequisites | `planned` | Increment-specific acceptance and quality evidence |

V0 is complete only after V0.3 is validated. The V0 increments are a non-production validation track and do not authorize production MVP implementation.

## 3. MVP decision traceability

Each accepted functional decision has an owning increment. Related increments may implement supporting parts, but the owning increment must demonstrate the decision explicitly in its acceptance evidence.

| Decision | Accepted subject | Owning increment(s) | Notes and boundary |
| --- | --- | --- | --- |
| DF-01 | First-release scope: garden memory, planning, recommendations, offline use, synchronization, backup | M1–M6; V0.1–V0.3 validate the first value path | V0 is not the MVP release |
| DF-02 | Typed spaces and optional nesting | M1.2, M1.3 | No geometry or type-derived climate rules |
| DF-03 | Crop lots, shared origin, and location-based lineage | M2.3, M2.4, M3.1, M3.4 | History is inherited by identity, not copied |
| DF-04 | Plans separate from actual cultivation and support partial execution | M2.1, M2.2, M2.3 | Publishing a plan does not create actual cultivation |
| DF-05 | Tasks, interventions, observations, harvests, and idempotent completion | M3.1, M3.2 | Work may be recorded without a task |
| DF-06 | Corrections, voids, archiving, and retained history | M3.3, M3.4 | Permanent deletion remains deferred |
| DF-07 | Minimal external catalog plus usable free-form entries | P0.7a, V0.1, M2.1, M5.1 | Catalog availability never blocks a free-form write |
| DF-08 | Cold-risk and sowing/planting-window recommendations | P0.7a, V0.3, M5.3, M5.4 | Rules, thresholds, and abstention need reviewed evidence |
| DF-09 | France, outdoor/unheated shelter context, approximate location, optional providers | P0.7, M5.1, M5.2, M5.3, M5.4 | Provider and precision choices remain explicit decisions |
| DF-10 | In-application cold-risk alerts | M5.3 | Closed-application notifications are deferred |
| DF-11 | Local-first synchronization for one gardener's devices | P0.5a, V0.2, V0.3, M1.3, M4.1 | Protocol conflicts are explicit; full domain resolution is later |
| DF-12 | Standalone use, configurable server access, and account isolation | P0.5a, M4.1 | V0 uses only the constrained test-only access profile |
| DF-13 | Compatible merge and explicit contradictory conflict handling | M4.2 | No silent last-write-wins behavior |
| DF-14 | Complete manual backup, restoration, and backup reminder | P0.6, M4.3 | Format and synchronization-state semantics require specification first |

## 4. Domain acceptance traceability

The full domain model is implemented progressively. V0 covers only its stated subset; the owner below is the first increment responsible for demonstrating each scenario fully.

| Scenario | Acceptance subject | Owning increment | V0 relevance |
| --- | --- | --- | --- |
| AC-DM-01 | A plan does not create actual cultivation | M2.2 | V0.2 creates a plan only |
| AC-DM-02 | Sowing creates a linked actual cycle | M2.3 | Deferred beyond V0 |
| AC-DM-03 | Unknown location and quantity are valid | M2.3 | V0.2 preserves unknown plan context |
| AC-DM-04 | A complete split closes the parent | M2.4 | Deferred beyond V0 |
| AC-DM-05 | A partial move is represented by a split | M2.4 | Deferred beyond V0 |
| AC-DM-06 | A complete move preserves lot identity | M2.4 | Deferred beyond V0 |
| AC-DM-07 | Shared history is visible but not duplicated | M3.1 | Deferred beyond V0 |
| AC-DM-08 | Partial completion leaves the cycle active | M2.4 | Deferred beyond V0 |
| AC-DM-09 | Closing all leaf lots completes the cycle | M2.4 | Deferred beyond V0 |
| AC-DM-10 | Harvest totals retain unknowns | M3.1 | Deferred beyond V0 |
| AC-DM-11 | A correction changes the current projection, not history | M3.3 | Deferred beyond V0 |
| AC-DM-12 | An accidental duplicate is voided, not deleted | M3.3 | Deferred beyond V0 |
| AC-DM-13 | Task completion is idempotent | M3.2 | Deferred beyond V0 |
| AC-DM-14 | Partial task work remains explicit | M3.2 | Deferred beyond V0 |
| AC-DM-15 | Undoing completion preserves the intervention audit | M3.2 | Deferred beyond V0 |
| AC-DM-16 | Space reorganization preserves historical meaning | M1.2 | Deferred beyond V0 |
| AC-DM-17 | An occupied space cannot be archived | M1.2 | Deferred beyond V0 |
| AC-DM-18 | Concurrent contradictory corrections become a conflict | M4.2 | V0.2 validates protocol conflict representation only |
| AC-DM-19 | A delayed entry uses business time for validity | M2.4 | Deferred beyond V0 |
| AC-DM-20 | Archiving changes visibility only | M3.4 | Deferred beyond V0 |
| AC-DM-21 | Partial plan realization retains the abandoned remainder | M2.2 | Deferred beyond V0 |
| AC-DM-22 | Partial lot completion first creates separate lots | M2.4 | Deferred beyond V0 |

## 5. Explicit exclusions

The following are not MVP commitments or V0 prerequisites unless a later accepted decision changes their status:

- perennials and trees;
- specialized space behavior, geometry, mapping, and automatic climate/capacity/protection inference;
- collaboration and shared gardens;
- catalog contribution workflows;
- notifications while the application is closed;
- selective permanent deletion;
- rotations, companion planting, seasonal reviews, photos, diagnosis, IoT, and conversational interaction;
- social networking, marketplaces, commerce, physical automation, and general-purpose AI; and
- production authentication, populated-server connection, full domain conflict-resolution UI, catalog HTTPS acquisition, compaction, and complete reconciliation in V0.

The exclusions preserve the boundary between accepted MVP scope, the limited V0 validation track, and exploratory product direction. They do not prevent future implementation after a new decision and dependency review.

## 6. Cross-repository and gate dependencies

```text
P0.1
├── P0.2a ───────────────┐
├── P0.4a ── P0.5a ──────┼── E10 ── V0.2 ──┐
└── P0.7a ── plants V1.4 ─┘                 ├── V0.3 ── V0 complete
                         E10 ── V0.1 ───────┘
```

- Foundation E10 is the technical authorization for V0 implementation.
- `hortinis-plants` V1.4 is required for V0.1 and must provide the generated artifact, pinned schemas, and conformance fixtures.
- Foundation G6 is required before production MVP increments.
- Product specifications remain prerequisites even when a technical gate has passed.

## 7. P0.1 completion evidence

P0.1 is accepted because this tracker:

- registers the product and V0 increments with statuses and prerequisite fields;
- maps DF-01 through DF-14 to owning increments;
- maps AC-DM-01 through AC-DM-22 to owning increments;
- records V0, MVP, technical-gate, and cross-repository boundaries; and
- lists explicitly deferred or exploratory capabilities.

Validation is documentation-only: review the tables and links, run the repository pre-scaffold checks, and confirm that no executable product behavior or implementation technology was introduced.
