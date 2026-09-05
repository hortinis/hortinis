# Hortinis — Open questions and design handoff

- Consolidated on: 2026-09-05.
- Status: unresolved questions, specification work, and proposed next steps.
- Accepted requirements: [functional decisions and MVP scope](functional-decisions.md).
- Long-term context: [product direction](product-direction.md).
- Technical references: [architecture](../architecture/README.md) and [open technical decisions](../architecture/open-decisions.md).

## 1. Resuming the work

Read the functional decisions and repository instructions first. Do not reopen an accepted decision merely because an older report suggested something else.

The repository is at the documentation stage: foundation technologies are selected, but no executable scaffold exists yet. Continuing design is not a request to implement features.

Do not treat the proposed scenarios or horizons as approved decisions. Record the rationale and scope impact of each new decision. New technical selections require an ADR. Write all repository content in English, as required by [ADR-0018](../architecture/decisions/0018-repository-language.md).

O-01 through O-03 are now resolved in the domain specification. The next domain-design conversation can derive the physical data model or API contracts from it; the next unresolved product topic is O-04. For long-term strategy, start with the product direction and section 5 below. Avoid opening every topic at once.

## 2. Domain model and workflows

O-01 through O-03 are resolved by the [domain glossary, lifecycle rules, and acceptance scenarios](domain-model.md), which is authoritative for the details.

| Question | Accepted decisions | Remaining presentation work |
| --- | --- | --- |
| O-01 — Plans, lots, cycles, and time | DF-03 and DF-04; separate plans and actual cycles, lineage, partial execution, precise time uncertainty, and recoverable history | None in the domain model |
| O-02 — Spaces and multiple gardens | DF-02; typed spaces, optional nesting, stable identities, and separate garden boundaries | Active-garden selection and cross-garden read views under O-04 |
| O-03 — Journal, tasks, and corrections | DF-05 and DF-06; tasks, interventions, corrections, voiding, undo, and append-only history | None in the domain model |

### O-04 — UX and preferences

Determine final navigation, home-view content, quick access to observations/interventions/harvests, journal filters, and presentation of planned versus actual information.

Define MVP scope for the four preference dimensions, unknown experience, defaults, explicit settings, and their scope per device or gardener. Future estimates must not trigger opaque interface changes.

Do not automatically adopt the menus, enums, or class models quoted in older reports.

## 3. Knowledge, weather, and recommendations

### O-05 — Initial catalog and coverage

Accepted decisions: DF-07 and DF-09.

Determine the initial plant list, necessary species/cultivar data, provenance, data license, quality and uncertainty, responsibility for rule validation, and test examples.

Specify what France covers in initial validation, including territories, climate contexts, and local limitations. Support for different climates is desired, but no zoning scheme is selected.

The canonical catalog is external: agree on the minimal reference dataset for validating Hortinis with the catalog project, without duplicating catalog maintenance in the user domain.

The distribution format is **already selected in ADR-0014**. Remaining work includes initial acquisition, first-use offline availability, updates, retired references, and linking free-form entries without losing history.

### O-06 — Weather provider and privacy

Accepted decision: approximate position, outdoor growing and unheated shelters, replaceable optional provider.

Determine useful precision per use case, position entry and transformation, retained and transmitted data, provider access from client or server, activation without an account or personal server, coverage, license, availability, and possible cost.

Establish required weather fields, refresh frequency, forecast horizon, and caching policy. Distinguish absent, stale, and insufficiently precise data. Select providers and technical mechanisms only after this analysis, recording an ADR where needed.

### O-07 — Rules for both recommendations

Accepted decision: both DF-08 cases are included; there is no need to choose between them.

For each, determine applicability, required factors, documented thresholds, relevant time period, unknowns, abstention conditions, confidence, explanation, and behavior under shelter.

Define the proposal lifecycle: reevaluation, replacement, expiration, postponement, dismissal, task conversion, execution, and review of past reasoning. Specify which rule and data versions must be retained to explain an earlier decision.

Message taxonomy, urgency, impact, and confidence must be distinguished. The multiplicative priority formula from the reports is not adopted.

Recommendations must be able to request an observation. An absent recorded event does not prove that an action did not occur.

## 4. Continuity, access, and recovery

### O-08 — Configurable access and ADR-0013

Accepted decision: account-free application use, optional accounts on individual servers, required accounts on shared instances; collaborative gardens deferred.

[ADR-0013](../architecture/decisions/0013-identity-and-storage-defaults.md) provides built-in local accounts, cookie sessions, and optional federation. An addition or revision must formalize individual mode without an account, its access control, data scope, and possible transition to an instance with accounts. Authentication-library selection remains open.

This work reconciles an accepted product decision with the architecture; it does not ask again whether accounts must be mandatory everywhere. The server's default setting has not been selected.

Also specify session expiry, offline local access, sign-out, device revocation, and consequences for local data, consistently with existing technical questions.

### O-09 — Connecting existing data and handling conflicts

Accepted decisions: DF-11 through DF-13.

Determine how local data connects to an empty or already-used server, adding devices, changing servers, ownership identity, and isolation between accounts on a shared instance.

Establish business compatibility rules, the visible state during a conflict, offered choices, and conflicts involving relationships, corrections, or archiving. Two different fields can form an invalid combination.

The [ADR-0010 protocol](../architecture/decisions/0010-synchronization-protocol-model.md) is already selected, including UUIDv7, idempotency, revisions, and recovery after compaction. Do not return to UUIDv4 or an open protocol choice based on the older reports. Automatic reconciliation must respect those guarantees and explicit conflicts.

### O-10 — Backup, restoration, and archiving

Accepted decision: complete manual export, restoration, and a discreet reminder; selective permanent deletion deferred.

Determine versioned format, integrity, included data and references, handling of conflicts and pending operations, cross-version compatibility, and the last-backup indication. Do not implicitly include session secrets in a business-data export.

Define restoration on an empty or already-used device, interaction with a server, and prevention of duplicates or overwrites. Set reminder frequency and user-facing errors.

Specify server backups and restoration. Distinguish them from business-data exports and imports from other applications, which are not committed for the MVP.

Deferring selective deletion does not settle erroneous entries, complete local erasure, account closure, or operational retention rules.

### O-11 — Quality, validation, and evidence of value

Define measurable criteria for field workflows, accessibility, supported data volumes, target browsers/devices, and behavior when local storage fails or is lost.

Distinguish three stages: current pre-scaffold documentation checks; technical foundation validation without business features; progressive business validation after the foundation is validated.

Proposed cross-cutting scenarios for future specifications:

1. Create a garden without an account, work offline, connect it to a server, and retrieve it on a second device without loss or duplication.
2. Plan a crop, execute only part of the plan, and split lots while preserving intentions and shared history.
3. Complete a task, correct its intervention, and synchronize repeatedly without duplicates.
4. Edit information on two devices: merge compatible changes or explicitly resolve a contradiction.
5. Evaluate cold risk using a recent forecast and then a stale one, with and without protection information.
6. Advise on a planned crop without a location, then reevaluate after placement.
7. Restore a backup containing lot relationships, corrections, and references absent from the current catalog.

These scenarios do not replace the protocol cases required by ADR-0010 or the detailed acceptance criteria still to be written.

North Star Metrics and individual KPIs in the sources are hypotheses. Do not treat them as authorized instrumentation: [ADR-0017](../architecture/decisions/0017-privacy-preserving-web-analytics.md) prohibits persistent individual journeys and gates measurement activation. Distinguish interviews, voluntary trials, and permitted measurements.

## 5. Long-term strategy questions

The [product direction](product-direction.md) now holds the durable ambition, exploratory horizons, candidate ideas, and review method. They are distinct from release commitments.

Questions for the next strategic discussion:

- What concrete outcomes should a gardener achieve after two or three seasons?
- Which audiences and garden types remain priorities: amateur vegetable gardens, mixed gardens, shared gardens, or professional production?
- Which problem justifies the next extension: planning, continuous harvests, understanding failures, perennial history, or coordination between people?
- What evidence would show better decisions without incorrectly attributing agricultural results to the software?
- Which uses should remain outside the product even when technically possible?
- What maintenance, horticultural validation, and catalog stewardship capacity can the project sustain?

After each decision, update the direction where necessary and record any release commitment in the functional decisions or the next release specification. Keep unvalidated ideas labeled as such.

## 6. Suggested handoff prompt

> We are continuing the design of Hortinis. Read AGENTS.md, docs/product/product-direction.md, docs/product/functional-decisions.md, docs/product/domain-model.md, docs/product/open-questions.md, and the relevant ADRs. Functional and domain decisions are accepted; do not reopen them based on older reports. The executable scaffold does not yet exist. Start by summarizing the topic, then ask a small group of focused questions. Derive the physical data model or API contracts from the domain specification, address O-04 or a later open question, or use section 5 and the product direction for long-term strategy. Distinguish decisions, proposals, and open questions. Keep all repository content in English. Do not implement features or modify documents without an explicit request in this new conversation.
