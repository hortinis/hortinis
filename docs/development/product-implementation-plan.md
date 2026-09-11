# Hortinis — Product implementation plan

- Status: proposed; product implementation not started.
- Scope: product capabilities from the validated technical foundation through the MVP and evidence-gated post-MVP work.
- Authority: [functional decisions and MVP scope](../product/functional-decisions.md), [domain model](../product/domain-model.md), and [product direction](../product/product-direction.md).
- Technical prerequisite: the [foundation readiness gate](foundation-implementation-plan.md#foundation-readiness-gate) must pass before production business-feature implementation begins. The limited V0 track may begin after the [V0 technical readiness gate](foundation-implementation-plan.md#v0-technical-readiness-gate).

## 1. Purpose and delivery model

This plan translates the accepted product decisions into independently reviewable product increments. It does not reopen accepted decisions and does not turn exploratory ideas into release commitments.

Product specifications can progress while the technical foundation is being built. V0 product code starts
only after the V0 technical readiness gate and its reduced product specifications are accepted. Production
product code starts only after V0.3, the complete foundation readiness gate, and the feature-specific
decisions required by an increment are accepted.

The delivery unit is a vertical slice. Each product increment includes, as applicable:

- accepted workflow and acceptance scenarios;
- TypeScript rules and coordinating services inside the Angular application;
- Dexie persistence, migration, and atomic local outbox recording;
- OpenAPI and schema changes before adapter implementation;
- Java rules and coordinating services inside the Spring Boot application, with PostgreSQL persistence;
- synchronization, retry, and conflict behavior;
- Angular interaction and accessibility behavior;
- shared TypeScript/Java conformance fixtures;
- offline, reload, migration, and end-to-end validation;
- updated user, deployment, and operational documentation.

No increment may introduce a mutable CRUD representation that loses stable identity, revisions, uncertainty, lineage, effective placements, or synchronization state.

### 1.1 Ordering and technical-work model

Identifiers group related capabilities; they do not by themselves define a total execution order. The
`Depends on` field is authoritative. In particular, work in later-numbered capability groups may proceed
when its listed dependencies are complete, even while an unrelated earlier-numbered group remains open.

The two plans meet at named validation reports:

- foundation E10 is the sole technical authorization for V0 implementation;
- foundation G6 is the sole technical authorization for production product implementation; and
- passing either report does not replace the product specifications listed by an increment.

Feature- or release-specific foundation work may still be a direct dependency after those entry gates;
foundation F3b is required by M6, for example.

Each implementation increment also lists its direct `Technical work`. Those bullets are part of the
increment, not separate untracked follow-up work. They must be split into independently reviewable changes
when implementation begins, with contract and migration changes preceding their adapters. Transitive
foundation work is referenced through E10 or G6 instead of repeating every foundation identifier, except
for a feature- or release-specific dependency.

## 2. Product readiness work

These items may proceed during foundation implementation. They produce specifications and decisions, not business features.

### P0.1 — Establish the product tracker

- Status: `validated`.
- Deliverable: [product tracker](product-tracker.md).
- Scope: record dependencies, exclusions, acceptance criteria, decision references, and validation evidence for every product increment.
- Acceptance: every MVP decision and domain acceptance scenario maps to one or more increments; exploratory capabilities are visibly excluded from the MVP. Evidence is maintained in the product tracker.

### P0.2 — Resolve the MVP experience (O-04)

- Status: `planned`.
- Depends on: P0.1.
- Scope: define navigation, active-garden selection, home view, quick recording, journal filters, planned-versus-actual presentation, history access, and offline/pending/synchronized/conflict states.
- Also define the MVP preference dimensions, defaults, explicit settings, and whether each setting is per device or per gardener.
- Excludes: opaque experience-level feature locking and menus copied from earlier proposals.
- Acceptance: the primary MVP journeys are reviewable without implementation-specific assumptions.

#### P0.2a — Define the first-test journey

- Status: `planned`.
- Depends on: P0.1.
- Scope: define the minimum screens and states for local catalog import, garden creation, plant selection,
  cultivation planning, synchronization and one recommendation result.
- Acceptance: the V0 journey exposes local, pending, synchronized, failed and protocol-conflict states
  without implying that complete MVP navigation or preferences are settled.

### P0.3 — Define the product quality envelope (O-11)

- Status: `planned`.
- Depends on: P0.1.
- Scope: supported browsers and devices, accessibility target, supported data volumes, local-write latency target, storage failure behavior, migration failure behavior, and field-workflow success criteria.
- Acceptance: these criteria are measurable and can be used as release gates.

### P0.4 — Derive the business record and operation specification

- Status: `planned`.
- Depends on: P0.1.
- Scope: implementation-neutral records, commands, revisions, current projections, ownership scope, business-time precision, quantities and unknowns, placements, lineage, allocations, voids, and terminal states.
- Acceptance: the physical model and contracts preserve the distinctions in the domain specification and support AC-DM-01 through AC-DM-22 without a generic mutable `crop` record.

#### P0.4a — Define the V0 business contract subset

- Status: `planned`.
- Depends on: P0.1.
- Scope: define only the garden, plant-selection and cultivation-plan records, their stable identifiers,
  revisions, commands, current projections, ownership scope and synchronization operations.
- Acceptance: the subset preserves historical plant labels, optional catalog references, unknown plan
  context and append-only revisions without introducing a generic mutable `crop` record.

### P0.5 — Resolve access and business synchronization (O-08 and O-09)

- Status: `planned`.
- Depends on: P0.4.
- Scope: standalone use, individual-server connection, shared-instance accounts, local-to-server transition, device enrollment and revocation, ownership/isolation, compatible merges, contradictory conflicts, and conflict resolution for relationships, corrections, and archiving.
- Acceptance: the access behavior is documented and any required technical selection is recorded in an
  ADR before production synchronized garden data is implemented. V0 may use only the constrained
  test-only profile defined by P0.5a.

#### P0.5a — Define the V0 synchronization scope

- Status: `planned`.
- Depends on: P0.4a.
- Scope: map the P0.4a commands to the accepted operation-journal protocol; define one synchronization
  scope, dependent-operation ordering, expected-revision failures and the test-only access profile used by
  automated and developer-run validation.
- Excludes: production authentication, connecting existing populated servers, domain merge rules,
  conflict-resolution UI, compaction and full reconciliation.
- Acceptance: V0 uses the production outbox and wire boundaries, never silently applies last-write-wins,
  and the test-only access profile cannot be enabled in a production build.

### P0.6 — Specify backup and restoration (O-10)

- Status: `planned`.
- Depends on: P0.4 and P0.5.
- Scope: versioned business export, integrity, included references and history, pending operations, restoration into empty and non-empty devices, duplicate prevention, server interaction, reminder behavior, and server operational backups.
- Acceptance: restoration semantics are explicit and testable before backup UI or APIs are implemented.

### P0.7 — Specify catalog, weather, and recommendations (O-05 through O-07)

- Status: `planned`.
- Depends on: P0.1.
- Scope: initial plant list and provenance, France coverage, catalog quality and update behavior, approximate location, weather fields and freshness, provider boundaries, cold-risk rules, sowing-window rules, abstention, explanation, versions, and recommendation lifecycle.
- Catalog boundary: the external catalog owns stable plant identity, plant-specific cultivation parameters, applicability, evidence, licence and confidence. Hortinis owns garden context, current weather, rule evaluation, scoring, explanations, user overrides and recommendation lifecycle.
- Catalog integration: the web application requires a catalog-artifact acquisition adapter separate from the Dexie persistence component in responsibility, within the same Angular application. It must support operator-selected local artifacts and authenticated HTTPS acquisition, schema and hash validation, atomic activation, rollback, retired references and quota or interrupted-import recovery.
- Contract location: the pinned consumer copy of the language-neutral manifest and entry contracts belongs under `contracts/catalog/`; the catalog project remains the upstream publisher of the versioned contract and release artifacts.
- Acceptance: each recommendation has reviewed examples, required factors, thresholds, missing-data behavior, and retained rule/data versions. Provider or transport selections require ADRs where applicable.

#### P0.7a — Local catalog validation contract

- Status: `planned`.
- Depends on: P0.1.
- Scope: define the local-validation artifact profile, representative plants and rules, provenance and
  licence expectations, missing and retired reference behavior, catalog and rule version retention, and
  expected recommendation, limitation and abstention examples.
- Excludes: HTTPS acquisition, release discovery, update polling, weather-provider selection and the
  complete recommendation lifecycle.
- Acceptance: the local artifact and its consumer fixtures specify the fields Hortinis requires and the
  same verification and activation path can later accept an HTTPS artifact.

P0.7a is the prerequisite for the local-validation slice. The remainder of P0.7 remains required for
production catalog and recommendation behavior.

## 3. V0 product-validation track

V0 is a synchronized, non-production vertical slice for testing the first Hortinis value path before the
complete foundation and MVP are ready. It uses real domain records and synchronization boundaries, not a
temporary CRUD model.

The execution order is: complete P0.2a, P0.4a, P0.5a, and P0.7a while the foundation progresses; validate
foundation E10; implement V0.1 and V0.2 in either order or in parallel; then complete V0.3. V0.3 is the
single completion point for the V0 product-validation objective.

### V0.1 — Local catalog acquisition

- Status: `planned`.
- Depends on: foundation E10 and P0.7a.
- Scope: accept an explicitly selected flat set of ADR-0014 artifact files; validate the pinned manifest
  and entry schemas, compatibility, chunk hashes, sizes and entry counts; stage and atomically activate the
  catalog in Dexie; support offline lookup and one missing or retired-reference fixture.
- Technical work: pin the consumer schemas and fixtures under `contracts/catalog/`; add the artifact-source
  boundary, validation and activation rules, a versioned Dexie catalog migration, the local-file adapter,
  import and lookup UI states, and unit, migration, offline-reload, failure-path, and browser tests.
- Excludes: HTTPS, release discovery, updates, download resumption, incremental replacement, advanced
  rollback and quota recovery.
- Acceptance: invalid or incomplete artifacts never replace the active catalog, and acquisition provides
  artifact bytes through the same source boundary later used by HTTPS.

### V0.2 — First synchronized business records

- Status: `planned`.
- Depends on: foundation E10, P0.2a, P0.4a, and P0.5a.
- Scope: create one garden, select a plant and create a simple cultivation plan locally; atomically enqueue
  their operations; push and accept them through Spring and PostgreSQL; pull them into a second browser;
  expose pending, synchronized, failed and protocol-conflict states.
- Technical work: define the V0 record, operation, result, and error contracts first; add matching
  TypeScript and Java rules and shared fixtures; add Dexie and Flyway migrations; implement atomic local
  projection/outbox and server acceptance transactions; extend the HTTP adapters; add the minimum
  accessible UI; and test offline writes, reload recovery, retries, dependent ordering, conflicts, and
  two-browser retrieval.
- Excludes: deletion, archival, complete conflict handling, multi-account access and connection to an
  already populated server.
- Acceptance: a second browser retrieves the same stable garden, plan and catalog reference without
  duplicates; service unavailability does not block new local work; reload preserves pending work.

### V0.3 — First integrated product test

- Status: `planned`.
- Depends on: V0.1 and V0.2.
- Scope: exercise the first-test journey in two browser contexts, with the local catalog acquired
  independently by each client and one reviewed sowing or planting-window rule evaluated from explicit
  local context.
- Technical work: implement the reviewed recommendation rule as a pure, versioned rule with explanation,
  limitation, and abstention results; add shared examples where the rule is represented in both runtimes;
  integrate its presentation; and run the complete two-browser, offline-reload, service-failure, missing-
  catalog, and synchronization-state scenario with recorded evidence.
- Acceptance: a gardener can create and synchronize a plan, see the referenced plant when the catalog is
  installed, retain the historical label when it is absent, and receive an explainable recommendation,
  limitation or abstention after offline reload.

V0 completion validates product and domain assumptions only. It does not complete M1, M2 or M5, satisfy
the full foundation readiness gate, or authorize an MVP release.

## 4. MVP increment plan

All MVP implementation is blocked until V0.3 and foundation G6 are validated. After those gates, follow
the explicit dependencies below: start with M1.1; M1.2 and M2.1 may then proceed independently; M1.3
follows M1.2, while the M2 chain follows M2.1. Catalog work may begin at M5.1 once M2.1 and P0.7 are
complete rather than waiting for M3 or M4. M3 follows the required space and cultivation work. M4.1 and
M4.3 may proceed independently after their prerequisites; M4.2 follows M4.1. Recommendation increments
begin as soon as their M2, M3, and M5 inputs exist. M6 is last.

P0.3 must be accepted before an MVP increment is declared validated against its quality criteria. P0.6 is
needed by M4.3, and P0.7 by M5; they need not block unrelated earlier implementation.

### M1 — Garden and space memory

#### M1.1 — Garden creation and selection

- Status: `planned`.
- Depends on: V0.3, foundation G6, P0.2, P0.4, and P0.5.
- Scope: account-free local garden creation, multiple gardens, active-garden selection, stable identity, rename, and archive visibility.
- Technical work: define contracts and migrations first; implement garden rules, Dexie projections and
  atomic outbox writes, Spring/PostgreSQL acceptance and ownership isolation, synchronization adapters,
  accessible selection UI, shared conformance fixtures, and offline, reload, migration, and end-to-end tests.
- Acceptance: a garden can be created, reopened, and queried entirely offline; records cannot cross garden boundaries.

#### M1.2 — Typed spaces

- Status: `planned`.
- Depends on: M1.1.
- Scope: accepted descriptive space types, optional arbitrary-depth nesting, rename, effective-time reparenting, historical paths, and archive restrictions for occupied spaces.
- Technical work: add space commands, revisions, effective placements, contracts, Dexie and Flyway
  migrations, pure hierarchy rules in both runtimes, synchronized projections and UI, cycle-prevention and
  boundary fixtures, and local/server integration tests.
- Excludes: geometry and automatic type-derived climate, capacity, or protection rules.
- Acceptance: AC-DM-16 and AC-DM-17 pass locally and through synchronized operations.

#### M1.3 — Expanded garden and space synchronization

- Status: `planned`.
- Depends on: M1.1 and M1.2. Foundation G6 is inherited through M1.1.
- Scope: extend the synchronized V0 behavior to complete garden and space workflows with local-first writes, retries, reload recovery, second-device retrieval, and explicit simple-record conflicts.
- Technical work: complete the operation mappings, tombstones, projections, retry and reconciliation
  handling for gardens and spaces; expose synchronization and conflict states; and add repeated-retry,
  second-device, interrupted-exchange, generation-rollover, and reconciliation tests.
- Acceptance: local work remains usable while the server is unavailable; repeated synchronization creates no duplicates.

### M2 — Planning and actual cultivation

#### M2.1 — Plant selections

- Status: `planned`.
- Depends on: M1.1 and P0.4.
- Scope: free-form plant and variety names, optional stable catalog reference, historical label retention, and missing/retired reference representation.
- Technical work: add contracts, local and server migrations, immutable historical-label rules, optional
  catalog-reference projection, synchronized operations and UI, missing-reference fixtures, and offline and
  migration tests without making catalog availability a write prerequisite.
- Acceptance: a missing catalog entry can be used immediately and later linked without changing its historical label.

#### M2.2 — Cultivation plans

- Status: `planned`.
- Depends on: M2.1.
- Scope: plan states, optional location and target quantity, exact/approximate/ranged/month/unknown timing, publish behavior, and explicit remainder abandonment.
- Technical work: implement precision and quantity value rules, plan commands and revisions, Dexie and
  PostgreSQL projections, migrations, synchronized workflows and accessible UI, shared fixtures, and tests
  for publish, unknown values, offline reload, conflict, and remainder abandonment.
- Acceptance: AC-DM-01 and AC-DM-21 pass; publishing a plan never creates actual cultivation.

#### M2.3 — Cycles and root lots

- Status: `planned`.
- Depends on: M2.2.
- Scope: actual establishment events, unplanned existing crops, establishing/active cycles, root lots, plan links, and explicit plan allocations.
- Technical work: add establishment, cycle, root-lot, and allocation contracts and invariants; add local and
  server migrations and transaction boundaries; synchronize their dependent operation chains; implement
  projections and UI; and test planned and unplanned establishment with unknown context.
- Acceptance: AC-DM-02 and AC-DM-03 pass with unknown dates, quantities, and locations preserved as unknown.

#### M2.4 — Lot lineage, movement, and lifecycle

- Status: `planned`.
- Depends on: M2.3.
- Scope: atomic splits, complete and partial moves, quantity conservation when compatible, unknown balance, temporal validity, terminal lots, and derived cycle completion.
- Technical work: implement lineage and effective-placement rules as pure code in both runtimes; define
  compound operation contracts and atomic local/server transactions; add migrations, projections, and UI;
  synchronize dependent changes; and add shared examples plus property, rollback, conflict, and end-to-end
  tests.
- Acceptance: AC-DM-04 through AC-DM-09, AC-DM-19, and AC-DM-22 pass. Property-based tests cover acyclic lineage, placement intervals, and terminal-state invariants.

### M3 — Work and recoverable history

#### M3.1 — Journal and fast recording

- Status: `planned`.
- Depends on: M1.2 and M2.4.
- Scope: interventions, observations, harvests, losses, garden/space/cycle/lot targets, unknown quantities, inherited ancestor history, and current journal projections.
- Technical work: define append-only journal contracts and targeting rules; add Dexie and PostgreSQL
  migrations and projections; implement atomic recording and synchronized operations; build accessible fast-
  entry and filtered-history UI; and test inherited-history deduplication, offline entry, reload, and retry.
- Acceptance: AC-DM-07 and AC-DM-10 pass; inherited records are displayed clearly and counted once by stable identity.

#### M3.2 — Tasks and fulfillment

- Status: `planned`.
- Depends on: M3.1.
- Scope: task states, due periods, partial extent, completion-to-intervention creation, idempotent completion, and undo.
- Technical work: define task, fulfillment, and undo operations; add persistence migrations and projections;
  implement the completion transaction atomically in browser and server paths; add synchronized UI and
  reminders that require no external provider; and test repeated completion, partial fulfillment, undo,
  reload, and reconciliation.
- Acceptance: AC-DM-13 through AC-DM-15 pass locally, after reload, and after repeated synchronization.

#### M3.3 — Corrections, voiding, and audit

- Status: `planned`.
- Depends on: M3.1 and the revision behavior established in P0.4.
- Scope: append-only corrections, current projections, revision history, duplicate voiding, correction of relationships, and recalculation of totals and lifecycle projections.
- Technical work: add correction and void contracts, revision-chain invariants, audit projections and
  migrations; implement deterministic recalculation in both runtimes; synchronize conflicts without
  rewriting history; expose current and historical UI; and test duplicate, relationship, and total
  corrections across reload and reconciliation.
- Acceptance: AC-DM-11 and AC-DM-12 pass; earlier values remain accessible and stable record identities do not change.

#### M3.4 — Completion and archiving

- Status: `planned`.
- Depends on: M2.4 and M3.3.
- Scope: crop-cycle completion, independent archive visibility, current/history views, and retrieval of archived records.
- Technical work: define completion and archive operations distinct from deletion; add projections,
  indexes, migrations, synchronization mappings, filters, and UI; and test tombstone separation, history
  retrieval, conflicts, and second-device behavior.
- Acceptance: AC-DM-20 passes and no archive operation deletes or rewrites history.

### M4 — Continuity, access, and recovery

#### M4.1 — Server connection and additional devices

- Status: `planned`.
- Depends on: M1.3, P0.5, and M3.4.
- Scope: accepted access modes, local-to-server connection, populated-server connection, second-device bootstrap, sign-out, session behavior, and device revocation.
- Technical work: record the access technology decision before selection; define session, enrollment,
  bootstrap, revocation, and ownership contracts; implement secure browser and Spring adapters and
  persistence; migrate standalone state through the outbox/reconciliation boundary; add connection UI and
  operational guidance; and test isolation, expiry, revocation, populated-server connection, and recovery.
- Acceptance: a garden created without an account can be connected and retrieved on another device without loss or duplication.

#### M4.2 — Domain conflict resolution

- Status: `planned`.
- Depends on: M4.1 and P0.5.
- Scope: compatible merges, contradictory corrections, relationship conflicts, archive conflicts, visible conflict state, and gardener resolution that creates a new revision.
- Technical work: specify per-command merge and contradiction rules; implement pure rules and shared
  fixtures in both runtimes; persist competing proposals and resolutions; add accessible conflict UI; and
  test repeated reconciliation, resolution races, offline continuation, and audit retention.
- Acceptance: AC-DM-18 passes; neither proposal is silently discarded and independent local work remains usable.

#### M4.3 — Complete backup and restoration

- Status: `planned`.
- Depends on: P0.6 and M3.4.
- Scope: manual export, integrity validation, complete history restoration, absent catalog references, conflicts, and pending synchronization work.
- Technical work: version the export schemas and integrity metadata; implement streaming export/import
  boundaries, staging and atomic activation, migrations, collision and server-reconnection behavior, user
  UI and operator procedures; and test corrupted, partial, old-version, empty-device, and non-empty-device
  restoration without requiring a catalog or provider.
- Acceptance: restoration into empty and non-empty devices is deterministic, does not silently overwrite data, and reproduces lineage, revisions, allocations, and journal history.

### M5 — Catalog and decision support

#### M5.1 — Production catalog acquisition and lifecycle

- Status: `planned`.
- Depends on: P0.7 and M2.1. V0.3 and foundation G6 are inherited through M2.1.
- Scope: complete ADR-0014 acquisition through operator-selected local artifacts and authenticated HTTPS;
  add release discovery, updates, interrupted-import recovery, quota failure, retired references across
  versions, incremental replacement and production rollback behavior.
- Technical work: pin the production consumer contracts and conformance fixtures; implement local and HTTPS
  acquisition ports, authenticated transport configuration, staged Dexie migrations, atomic activation,
  rollback and quota recovery, lifecycle UI, and tests for interruption, integrity failure, update,
  retirement, and offline operation.
- Contract prerequisites: `contracts/catalog/` schemas and conformance fixtures must be pinned before
  adapter implementation. The catalog release must provide stable opaque identifiers and attribution/
  licence metadata.
- Acceptance: catalog acquisition failure never blocks core garden use; only schema- and integrity-valid
  entries activate; local and HTTPS sources use the same verification and activation pipeline.

#### M5.2 — Weather context

- Status: `planned`.
- Depends on: P0.7, M1.1, and M5.1.
- Scope: approximate location, outdoor/unheated-shelter distinction, protection information, cached forecasts, freshness states, and optional provider configuration.
- Technical work: record any provider selection in an ADR; define provider-neutral forecast and freshness
  contracts; implement opt-in adapters, minimized configuration, local cache migrations and UI; and test
  absent-provider, stale, imprecise, unavailable, and offline states without logging location or payloads.
- Acceptance: missing, stale, imprecise, and absent-provider states are explicit; indoor temperature is never inferred.

#### M5.3 — Forecast cold-risk recommendation

- Status: `planned`.
- Depends on: M2.4, M3.1, M5.1, and M5.2.
- Scope: in-app home and crop alerts for existing crops, explainable factors, uncertainty, abstention, versions, and observation requests.
- Technical work: implement versioned pure rules and shared examples, persist input/rule/data-version
  references and recommendation lifecycle, add deterministic reevaluation and accessible presentation, and
  test stale/missing inputs, abstention, user overrides, offline use, and the prohibition on inferred work.
- Acceptance: the recommendation never claims that an unrecorded intervention occurred and explains stale or insufficient data.

#### M5.4 — Sowing or planting window recommendation

- Status: `planned`.
- Depends on: M2.2, M5.1, M5.2, and P0.7.
- Scope: guidance for already selected crops, including plans with unspecified location or date, reevaluation, limitations, and historical rule/data versions.
- Technical work: implement versioned pure rules and shared examples, persist context and version
  references, integrate deterministic reevaluation and accessible plan UI, and test missing context,
  catalog retirement, stale weather, explicit limitations, abstention, and offline operation.
- Excludes: automatic selection of new crops.
- Acceptance: the recommendation remains useful with explicit limitations when context is missing and changes predictably when context is added.

### M6 — MVP release validation

- Status: `planned`.
- Depends on: M1 through M5, P0.3, foundation G6, and foundation F3b.
- Scope: integrated product hardening, accessibility, target-browser/device testing, storage failure and migration recovery, performance at the agreed data volume, deployment documentation, and field-trial preparation.
- Technical work: run and record the complete unit, property, conformance, migration, PostgreSQL,
  browser/device, accessibility, performance, offline, synchronization, reconciliation, backup/restore,
  container, same-origin, privacy, and autonomous-runtime matrix; close blocking findings; and publish the
  MVP validation report and release documentation.
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

## 5. Beyond-MVP plan

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

## 6. Cross-cutting definition of done

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
