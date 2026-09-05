# Hortinis — Product implementation plan

- Status: proposed; product implementation not started.
- Scope: product capabilities from the validated technical foundation through the MVP and evidence-gated post-MVP work.
- Authority: [functional decisions and MVP scope](../product/functional-decisions.md), [domain model](../product/domain-model.md), and [product direction](../product/product-direction.md).
- Technical prerequisite: the [foundation readiness gate](foundation-implementation-plan.md#foundation-readiness-gate) must pass before business-feature implementation begins.

## 1. Purpose and delivery model

This plan translates the accepted product decisions into independently reviewable product increments. It does not reopen accepted decisions and does not turn exploratory ideas into release commitments.

Product specifications can progress while the technical foundation is being built. Product code starts only after the foundation readiness gate is validated and the feature-specific decisions required by an increment are accepted.

The delivery unit is a vertical slice. Each product increment includes, as applicable:

- accepted workflow and acceptance scenarios;
- TypeScript domain and application behavior;
- Dexie persistence, migration, and atomic local outbox recording;
- OpenAPI and schema changes before adapter implementation;
- Java domain/application behavior and PostgreSQL persistence;
- synchronization, retry, and conflict behavior;
- Angular interaction and accessibility behavior;
- shared TypeScript/Java conformance fixtures;
- offline, reload, migration, and end-to-end validation;
- updated user, deployment, and operational documentation.

No increment may introduce a mutable CRUD representation that loses stable identity, revisions, uncertainty, lineage, effective placements, or synchronization state.

## 2. Product readiness work

These items may proceed during foundation implementation. They produce specifications and decisions, not business features.

### P0.1 — Establish the product tracker

- Status: `planned`.
- Scope: record dependencies, exclusions, acceptance criteria, decision references, and validation evidence for every product increment.
- Acceptance: every MVP decision and domain acceptance scenario maps to one or more increments; exploratory capabilities are visibly excluded from the MVP.

### P0.2 — Resolve the MVP experience (O-04)

- Status: `planned`.
- Scope: define navigation, active-garden selection, home view, quick recording, journal filters, planned-versus-actual presentation, history access, and offline/pending/synchronized/conflict states.
- Also define the MVP preference dimensions, defaults, explicit settings, and whether each setting is per device or per gardener.
- Excludes: opaque experience-level feature locking and menus copied from earlier proposals.
- Acceptance: the primary MVP journeys are reviewable without implementation-specific assumptions.

### P0.3 — Define the product quality envelope (O-11)

- Status: `planned`.
- Scope: supported browsers and devices, accessibility target, supported data volumes, local-write latency target, storage failure behavior, migration failure behavior, and field-workflow success criteria.
- Acceptance: these criteria are measurable and can be used as release gates.

### P0.4 — Derive the business record and operation specification

- Status: `planned`.
- Scope: implementation-neutral records, commands, revisions, current projections, ownership scope, business-time precision, quantities and unknowns, placements, lineage, allocations, voids, and terminal states.
- Acceptance: the physical model and contracts preserve the distinctions in the domain specification and support AC-DM-01 through AC-DM-22 without a generic mutable `crop` record.

### P0.5 — Resolve access and business synchronization (O-08 and O-09)

- Status: `planned`.
- Scope: standalone use, individual-server connection, shared-instance accounts, local-to-server transition, device enrollment and revocation, ownership/isolation, compatible merges, contradictory conflicts, and conflict resolution for relationships, corrections, and archiving.
- Acceptance: the access behavior is documented and any required technical selection is recorded in an ADR before synchronized garden data is implemented.

### P0.6 — Specify backup and restoration (O-10)

- Status: `planned`.
- Scope: versioned business export, integrity, included references and history, pending operations, restoration into empty and non-empty devices, duplicate prevention, server interaction, reminder behavior, and server operational backups.
- Acceptance: restoration semantics are explicit and testable before backup UI or APIs are implemented.

### P0.7 — Specify catalog, weather, and recommendations (O-05 through O-07)

- Status: `planned`.
- Scope: initial plant list and provenance, France coverage, catalog quality and update behavior, approximate location, weather fields and freshness, provider boundaries, cold-risk rules, sowing-window rules, abstention, explanation, versions, and recommendation lifecycle.
- Acceptance: each recommendation has reviewed examples, required factors, thresholds, missing-data behavior, and retained rule/data versions. Provider or transport selections require ADRs where applicable.

## 3. MVP increment plan

### M1 — Garden and space memory

#### M1.1 — Garden creation and selection

- Depends on: foundation readiness, P0.2, P0.4, and P0.5.
- Scope: account-free local garden creation, multiple gardens, active-garden selection, stable identity, rename, and archive visibility.
- Acceptance: a garden can be created, reopened, and queried entirely offline; records cannot cross garden boundaries.

#### M1.2 — Typed spaces

- Depends on: M1.1.
- Scope: accepted descriptive space types, optional arbitrary-depth nesting, rename, effective-time reparenting, historical paths, and archive restrictions for occupied spaces.
- Excludes: geometry and automatic type-derived climate, capacity, or protection rules.
- Acceptance: AC-DM-16 and AC-DM-17 pass locally and through synchronized operations.

#### M1.3 — First synchronized business slice

- Depends on: M1.1, M1.2, and the validated synchronization foundation.
- Scope: synchronize gardens and spaces with local-first writes, retries, reload recovery, second-device retrieval, and explicit simple-record conflicts.
- Acceptance: local work remains usable while the server is unavailable; repeated synchronization creates no duplicates.

### M2 — Planning and actual cultivation

#### M2.1 — Plant selections

- Depends on: M1.1 and P0.4.
- Scope: free-form plant and variety names, optional stable catalog reference, historical label retention, and missing/retired reference representation.
- Acceptance: a missing catalog entry can be used immediately and later linked without changing its historical label.

#### M2.2 — Cultivation plans

- Depends on: M2.1.
- Scope: plan states, optional location and target quantity, exact/approximate/ranged/month/unknown timing, publish behavior, and explicit remainder abandonment.
- Acceptance: AC-DM-01 and AC-DM-21 pass; publishing a plan never creates actual cultivation.

#### M2.3 — Cycles and root lots

- Depends on: M2.2.
- Scope: actual establishment events, unplanned existing crops, establishing/active cycles, root lots, plan links, and explicit plan allocations.
- Acceptance: AC-DM-02 and AC-DM-03 pass with unknown dates, quantities, and locations preserved as unknown.

#### M2.4 — Lot lineage, movement, and lifecycle

- Depends on: M2.3.
- Scope: atomic splits, complete and partial moves, quantity conservation when compatible, unknown balance, temporal validity, terminal lots, and derived cycle completion.
- Acceptance: AC-DM-04 through AC-DM-09, AC-DM-19, and AC-DM-22 pass. Property-based tests cover acyclic lineage, placement intervals, and terminal-state invariants.

### M3 — Work and recoverable history

#### M3.1 — Journal and fast recording

- Depends on: M1.2 and M2.4.
- Scope: interventions, observations, harvests, losses, garden/space/cycle/lot targets, unknown quantities, inherited ancestor history, and current journal projections.
- Acceptance: AC-DM-07 and AC-DM-10 pass; inherited records are displayed clearly and counted once by stable identity.

#### M3.2 — Tasks and fulfillment

- Depends on: M3.1.
- Scope: task states, due periods, partial extent, completion-to-intervention creation, idempotent completion, and undo.
- Acceptance: AC-DM-13 through AC-DM-15 pass locally, after reload, and after repeated synchronization.

#### M3.3 — Corrections, voiding, and audit

- Depends on: M3.1 and the revision behavior established in P0.4.
- Scope: append-only corrections, current projections, revision history, duplicate voiding, correction of relationships, and recalculation of totals and lifecycle projections.
- Acceptance: AC-DM-11 and AC-DM-12 pass; earlier values remain accessible and stable record identities do not change.

#### M3.4 — Completion and archiving

- Depends on: M2.4 and M3.3.
- Scope: crop-cycle completion, independent archive visibility, current/history views, and retrieval of archived records.
- Acceptance: AC-DM-20 passes and no archive operation deletes or rewrites history.

### M4 — Continuity, access, and recovery

#### M4.1 — Server connection and additional devices

- Depends on: M1.3, P0.5, and M3.4.
- Scope: accepted access modes, local-to-server connection, populated-server connection, second-device bootstrap, sign-out, session behavior, and device revocation.
- Acceptance: a garden created without an account can be connected and retrieved on another device without loss or duplication.

#### M4.2 — Domain conflict resolution

- Depends on: M4.1 and P0.5.
- Scope: compatible merges, contradictory corrections, relationship conflicts, archive conflicts, visible conflict state, and gardener resolution that creates a new revision.
- Acceptance: AC-DM-18 passes; neither proposal is silently discarded and independent local work remains usable.

#### M4.3 — Complete backup and restoration

- Depends on: P0.6 and M3.4.
- Scope: manual export, integrity validation, complete history restoration, absent catalog references, conflicts, and pending synchronization work.
- Acceptance: restoration into empty and non-empty devices is deterministic, does not silently overwrite data, and reproduces lineage, revisions, allocations, and journal history.

### M5 — Catalog and decision support

#### M5.1 — Minimal offline catalog

- Depends on: P0.7 and M2.1.
- Scope: ADR-0014 artifact acquisition, validation, activation, offline snapshot, updates, retired references, and free-form fallback.
- Acceptance: catalog acquisition failure never blocks core garden use; activated entries pass schema and integrity validation.

#### M5.2 — Weather context

- Depends on: P0.7, M1.1, and M5.1.
- Scope: approximate location, outdoor/unheated-shelter distinction, protection information, cached forecasts, freshness states, and optional provider configuration.
- Acceptance: missing, stale, imprecise, and absent-provider states are explicit; indoor temperature is never inferred.

#### M5.3 — Forecast cold-risk recommendation

- Depends on: M2.4, M3.1, M5.1, and M5.2.
- Scope: in-app home and crop alerts for existing crops, explainable factors, uncertainty, abstention, versions, and observation requests.
- Acceptance: the recommendation never claims that an unrecorded intervention occurred and explains stale or insufficient data.

#### M5.4 — Sowing or planting window recommendation

- Depends on: M2.2, M5.1, M5.2, and P0.7.
- Scope: guidance for already selected crops, including plans with unspecified location or date, reevaluation, limitations, and historical rule/data versions.
- Excludes: automatic selection of new crops.
- Acceptance: the recommendation remains useful with explicit limitations when context is missing and changes predictably when context is added.

### M6 — MVP release validation

- Depends on: M1 through M5 and the foundation validation report.
- Scope: integrated product hardening, accessibility, target-browser/device testing, storage failure and migration recovery, performance at the agreed data volume, deployment documentation, and field-trial preparation.
- Acceptance:
  - DF-01 through DF-14 are implemented.
  - AC-DM-01 through AC-DM-22 pass at the appropriate domain, integration, and end-to-end levels.
  - The seven cross-cutting O-11 scenarios pass.
  - Core writes work offline and survive reload.
  - Synchronization preserves local intent and exposes contradictions.
  - Backup restoration reproduces complete business history.
  - Both recommendations explain factors, uncertainty, and abstention.
  - The application operates without a configured external provider.
  - User, self-hosting, recovery, and privacy documentation is complete.
  - Voluntary field trials assess whether recording and advice are understandable; they do not claim causal improvement in crop outcomes.

Analytics are not an MVP dependency. Collection or contribution remains disabled until the separate ADR-0017 finalization gates are satisfied.

## 4. Beyond-MVP plan

Post-MVP work is outcome-gated. The following is a proposed sequence of candidate horizons, not a release commitment.

### B1 — Stabilize the first season

Use field trials, recovery exercises, and support feedback to reduce recording effort, improve explanations, and correct synchronization or restoration failures. Do not expand the model until the core memory loop is reliable.

### B2 — Seasonal continuity

Candidate capabilities are seasonal reviews, planned-versus-actual comparison, and reuse of a previous plan without copying actual events. Proceed only when gardeners can demonstrate that history changed a later planning decision.

### B3 — Better planning guidance

Candidate capabilities are staggered sowing, succession, and rotation checks. They require documented horticultural rules, sufficient space history, and explanations that distinguish evidence from inference.

### B4 — Richer garden lifecycles and observations

Candidate capabilities are perennial plants, trees, repeated production cycles, photos, structured measurements, and carefully bounded diagnosis assistance. Each requires a new domain specification, storage/privacy assessment, and evidence of need.

### B5 — Selected extensions

Collaboration, catalog contributions, outside-application notifications, and optional integrations require explicit decisions about permissions, attribution, governance, delivery reliability, and maintenance. They must not be inferred from the individual-gardener MVP.

Social networking, marketplaces, commerce, physical automation, and general-purpose AI remain outside this plan unless product positioning is explicitly reconsidered.

## 5. Cross-cutting definition of done

Every product increment must:

- trace behavior to an accepted decision or newly approved specification;
- preserve offline local commits and safe synchronization retries;
- validate domain behavior in both TypeScript and Java where duplicated;
- use stable identifiers, revisions, and explicit unknown values;
- preserve correction, void, lineage, placement, and audit history;
- expose pending, synchronized, failed, and conflicted states where relevant;
- include migration, reload, and failure-path tests;
- keep provider-specific behavior behind ports;
- avoid prohibited content in logs and avoid mandatory external services;
- include accessibility checks and English-language documentation;
- record validation commands, evidence, limitations, and follow-up risks.

At each seasonal review, record what users achieved, where the value loop failed, what evidence supports the next outcome, and which exploratory ideas remain intentionally deferred.
