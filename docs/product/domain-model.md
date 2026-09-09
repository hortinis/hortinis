# Hortinis domain glossary, lifecycle rules, and acceptance scenarios

- Decided on: 2026-09-05.
- Status: accepted product-domain specification for O-01 through O-03.
- Scope: conceptual domain model for planning, crop tracking, spaces, journal entries, tasks, and corrections. This document does not prescribe database tables, classes, endpoints, or interface layouts.
- Builds on: [functional decisions and MVP scope](functional-decisions.md).

## 1. Modeling principles

The domain distinguishes an intention from what happened. A **cultivation plan** records the intention; a **crop cycle** and its **crop lots** record actual cultivation. Neither side overwrites the other.

The following rules apply throughout the model:

- Unknown information remains unknown. The application does not invent dates, quantities, units, locations, stages, or causes.
- Business time and recording time are distinct. Delayed entry does not pretend that the record was created earlier.
- A correction creates a new revision of a record. It does not erase the accepted earlier revision.
- Lot lineage and space-location history use stable identities. Renaming, reorganizing, splitting, or moving does not disconnect earlier records.
- A journal entry is counted once by its stable identity, even when it is visible through several related views.
- Lifecycle status is not evidence that an unrecorded action occurred. For example, completing a lot does not imply a final harvest.
- Archiving changes normal visibility only. It is not completion, cancellation, correction, or deletion.

### Decision rationale and scope impact

| Decision | Rationale | Consequence for later models and contracts |
| --- | --- | --- |
| Separate plans from actual cycles | Intention must remain comparable with reality instead of being overwritten by it. | Plan fields and actual fields need distinct identities and lifecycles joined by explicit allocations. |
| Close a parent lot when it splits | An inactive parent gives all descendants one unambiguous origin and prevents a mutable “remainder” from owning events after the split. | Splitting is an atomic lineage operation; ancestor history is referenced and deduplicated, not copied. |
| Keep the same lot for a complete move | Location is a time-varying property, while physical group identity has not changed. | Lot placement needs effective intervals. A partial move composes splitting with new placements. |
| Revise or void records instead of overwriting or deleting them | Garden memory, delayed synchronization, and correction audit all require accepted earlier values to remain explainable. | Correctable records need stable identities, revision chains, and current projections. |
| Keep initial space types descriptive | The accepted scope needs typed spaces without unvalidated type-specific behavior. | Type values do not imply geometry, climate, protection, or capacity rules. |

## 2. Domain glossary

### Garden and spaces

| Term | Meaning | Important boundary |
| --- | --- | --- |
| **Garden** | The top-level context owned and managed by a gardener. Plans, spaces, cycles, tasks, and garden-level journal entries belong to one garden. | A garden is not a space and cannot be nested in another garden. |
| **Space** | A stable, typed place within a garden where work can occur or a crop lot can be located. A space may optionally have a parent space. | A space's identity survives renaming and reparenting. A lot has no more than one current space. |
| **Space type** | A descriptive classification with no automatic specialized behavior in the MVP. Initial values are `area`, `in_ground_bed`, `raised_bed`, `container`, `greenhouse`, `polytunnel`, `cold_frame`, and `nursery`. | Type does not determine temperature, protection, capacity, or nesting rules. Those facts must be recorded separately when needed. |
| **Space placement** | A time-bounded relationship between a space and its parent, or between a crop lot and its current space. | Earlier placements remain resolvable after reorganization. |

A space requires only a garden, a stable identity, a name, and a type. A parent and descriptive notes are optional. There is no arbitrary nesting-depth limit in the domain. A parent must belong to the same garden, and a space cannot be its own ancestor.

The type `nursery` represents a managed nursery place when the gardener wants it in the space tree. A crop may also be tracked before its location is known; creating a nursery space is not mandatory.

Hortinis can hold several gardens. Every plan, cycle, lot, space, task, and journal entry belongs to exactly one garden, and relationships cannot cross that boundary. Spaces and active crop cycles cannot be reassigned between gardens in the MVP. Choosing an active garden or offering a cross-garden read view is presentation work under O-04, not a domain identity or lifecycle rule.

### Crop intention and actuality

| Term | Meaning | Important boundary |
| --- | --- | --- |
| **Plant selection** | The plant or cultivar intended or cultivated, identified by a stable catalog reference or a gardener-supplied free-form name. | Attaching or updating a catalog reference does not replace the gardener's historical label. |
| **Cultivation plan** | An intention to cultivate one plant selection in one prospective crop cycle, with optional target quantity, location, and sowing or planting period. | It contains planned information only. Actual dates, places, work, and results belong to the cycle and lots linked to it. |
| **Plan allocation** | An explicit association between a comparable part of a plan target and an actual crop cycle. | Allocations measure realization only when plan and actual quantities are comparable. They are not inferred from harvests. |
| **Crop cycle** | One bounded actual cultivation undertaking for a plant selection, from the first establishment activity through the end of all derived lots. | It may be linked to one cultivation plan or be unplanned. It is not tied permanently to a calendar year. |
| **Crop lot** | A tracked physical group within one crop cycle that is homogeneous enough to share one current location and lifecycle. | One lot cannot be in two spaces at once. If only part changes location or lifecycle, the lot is split first. |
| **Lot lineage** | The directed, acyclic parent-child relationship created by splitting a lot. | The parent becomes inactive and is never reused as a container for its children. Each child then evolves independently. |
| **Crop** | An informal user-facing word for cultivated plants. | It is not a separate MVP domain entity. In precise rules, use cultivation plan, crop cycle, or crop lot. |
| **Season** | An optional gardener-facing label or reporting period, such as `2027` or `spring 2027`. | It is not a lifecycle, identity boundary, or substitute for dates. A cycle may cross seasons. |

One cultivation plan represents one intended cycle. Staggered sowings are separate plans in the MVP, although a later feature may group or copy them. One cycle can have many lots through splitting; all lots in a cycle share its plant selection and establishment origin.

The cycle boundary deliberately supports future perennials: a perennial plant may later remain the same crop subject while producing several management or production cycles. The MVP neither implements perennial subjects nor assumes that a cycle equals a year.

### Quantity and time

| Term | Meaning | Important boundary |
| --- | --- | --- |
| **Quantity observation** | A known value, optional unit, subject, and precision recorded at a point in the history, for example `10 plants`, `about 2 m²`, or `2.7 kg fruit`. | Unknown is valid. Values with different units or subjects are not silently converted or added. |
| **Loss** | An explicit decrease in a lot, with an optional known quantity and optional reason. | A lower later count does not by itself prove a loss or its cause. |
| **Harvest** | A journal record of produce taken from a crop lot or, when the exact lot is unknown, its crop cycle. It may have an unknown quantity. | A harvest does not complete a lot, and completion does not imply a harvest. |
| **Business time** | When an intention applies or an event occurred, represented as an exact local date, an approximate local date, a month, a closed date range, or unknown. | Precision is retained. The application does not coerce a month, range, or unknown value to a made-up day. |
| **Recorded time** | The exact instant at which a device accepted a record locally. | It supports audit and synchronization but is not the event's business date. |
| **Correction time** | The exact instant at which a correction revision was accepted locally. | Correcting an event date changes its current business time, not its original recorded time or revision history. |

Quantity conservation is enforced only when all quantities involved are known, have the same unit and subject, and describe the same split instant. Otherwise the relationship is recorded without a fabricated balance and may be marked incomplete.

### Work, journal, and corrections

| Term | Meaning | Important boundary |
| --- | --- | --- |
| **Task** | An intention to perform an action, with an optional due period, target, and known extent. | A task is not proof that work occurred. |
| **Intervention** | A journal entry stating that work was performed. It may exist independently or fulfill part or all of a task. | Viewing, accepting, or dismissing advice does not create an intervention. |
| **Observation** | A journal entry stating what the gardener noticed, without requiring a diagnosis. | An observation does not assert a cause unless the gardener explicitly records one. |
| **Journal** | A chronological projection of current, non-voided establishment events, movements, lifecycle events, interventions, observations, harvests, losses, and corrections relevant to a selected context. | The journal is a view, not a separate source of truth. Correction history and voided records remain accessible. |
| **Target** | The garden, a space, a crop cycle, or one or more crop lots to which a task or journal entry applies. | A crop target is optional: general garden and space work can be recorded without a crop. |
| **Correction** | An append-only revision that supersedes the current values of a correctable record and identifies the revision it corrects. | The stable record identity remains unchanged, and previous values remain accessible. |
| **Void** | A correction state saying that a record should no longer contribute to current views or calculations because it was accidental or duplicative. | Voiding is not physical deletion. A duplicate may reference the retained canonical record. |

The initial intervention action kinds are `sow`, `plant_or_transplant`, `water`, `fertilize`, `weed`, `mulch`, `protect`, `remove_protection`, `prune_or_train`, `treat`, and `other`. The action kind, target, and business time are sufficient to create an intervention; details and quantities are optional. Harvests and observations remain distinct record kinds rather than intervention action kinds.

Minimum business content remains deliberately small:

| Record | Required business content | Optional content |
| --- | --- | --- |
| Task | Garden, action kind or free-form action, and status | Specific target, due time, extent, notes |
| Intervention | Garden, action kind, target, and business time (which may be unknown) | Quantity, method, materials, notes, task fulfillment |
| Observation | Garden, target, business time (which may be unknown), and a free-form observation | Category, structured measurements, suspected diagnosis, notes |
| Harvest | Garden, crop-cycle or crop-lot target, and business time (which may be unknown) | Quantity, unit, produce description, notes |
| Loss | Garden, crop-lot target, and business time (which may be unknown) | Quantity, unit, reason, notes |

The target of an intervention or observation may be the garden itself, so no crop or space is required. Establishment, movement, split, and lifecycle records have operation-specific required relationships described in the lifecycle rules below.

## 3. Lifecycle rules

### 3.1 Cultivation plan

| State | Meaning | Allowed next states |
| --- | --- | --- |
| `draft` | The intention is being prepared and is excluded from committed-plan views. | `planned`, `abandoned` |
| `planned` | The intention is committed but has no realized allocation. | `partially_realized`, `realized`, `abandoned` |
| `partially_realized` | At least one linked cycle realizes part of a comparable target and an intended remainder is still open. | `realized`, `abandoned` |
| `realized` | The gardener has accounted for the whole intention as actual cultivation. | No operational transition |
| `abandoned` | The gardener will not pursue the remaining intention. Actual cycles and earlier allocations are retained. | No operational transition |

Archiving is an independent visibility flag allowed in any non-draft state. It does not change realization status. A plan with no comparable target moves from `planned` to `realized` only by an explicit gardener decision; Hortinis does not infer fulfillment from an arbitrary actual quantity.

Abandoning a partially realized plan abandons only its unallocated remainder. It does not abandon or complete linked cycles.

### 3.2 Crop cycle

| State | Meaning | Allowed next states |
| --- | --- | --- |
| `establishing` | Sowing or propagation has begun, and the cycle has not yet been explicitly recorded as established. | `active`, `abandoned` |
| `active` | Establishment has been explicitly recorded and at least one leaf lot is active. | `completed`, `abandoned` |
| `completed` | No active leaf lot remains and at least one leaf lot ended normally. Individual leaf lots may still record loss or abandonment. | No operational transition |
| `abandoned` | No active leaf lot remains and none ended normally, because establishment, cultivation, or tracking was stopped or lost. | No operational transition |

A cycle is created by an actual establishment event, not merely by publishing a plan. Sowing starts it as `establishing`. Planting, transplanting, an explicit established-stage record, or recording an already-existing crop starts or advances it to `active`; the application does not infer establishment merely from elapsed time. A root lot can be `active` while its cycle is still `establishing`: lot activity means that the physical group is being tracked, while cycle activity means that establishment has been confirmed.

The terminal cycle state is derived from its current leaf lots. While any leaf lot remains active, an established cycle remains `active`. With no active leaf lots, it becomes `completed` if at least one leaf lot completed normally; otherwise it becomes `abandoned`. Closing a user-facing “crop” means ending all active leaf lots with explicit outcomes; it is not a direct overwrite of the derived cycle state.

Archiving is again an independent visibility flag. Only a terminal cycle may be archived. An archived cycle remains available in history, plan comparisons, backup, and restoration.

### 3.3 Crop lot, splitting, and movement

| State | Meaning | Allowed next states |
| --- | --- | --- |
| `active` | The physical group is currently tracked, with a known or unknown location and quantity. | `split`, `completed`, `abandoned`, `lost` |
| `split` | The whole physical group was partitioned into two or more child lots. The parent is no longer active. | No operational transition |
| `completed` | Cultivation of the whole remaining group ended normally. | No operational transition |
| `abandoned` | The gardener deliberately stopped cultivating or tracking the whole remaining group. | No operational transition |
| `lost` | The whole remaining group is known to have been lost. | No operational transition |

Rules for lot changes:

1. A split is atomic: it closes one active parent as `split` and creates at least two active child lots in the same cycle.
2. A child records its immediate parent. The shared origin is found by following lineage to the root lot; a separate copied history is forbidden.
3. A complete move ends the lot's current space placement and starts another placement on the same lot. Its identity and lifecycle do not change.
4. A partial move first splits the parent. One child may remain in the old space and another may start in the new space.
5. Partial completion, abandonment, or loss uses the same split rule: create separate child lots for the portions that need different outcomes, then end the applicable children.
6. If an exact compatible parent quantity is known, exact compatible child quantities plus an explicit remainder or loss must account for it. A remainder that stays together is a child lot, not an active residue on the parent.
7. When any needed quantity is unknown or incompatible, the split is allowed with unknown balance. The application states that conservation was not verified.
8. A lot may temporarily have no known location. Assigning its first known space starts a placement; it is not a move from an invented nursery.
9. A terminal or split lot cannot receive a new event whose possible business time is entirely after its end. A delayed record whose time is valid or indeterminate under the temporal-validity rules below may be retained; a record proven to be outside the active interval is rejected.

Events recorded before a split target the ancestor lot. Descendant histories display those inherited events, clearly marked as occurring before the split. Events after the split target only the applicable child lots or the cycle. A cycle total deduplicates by journal-record identity, so an ancestor event visible under two children contributes once.

#### Temporal validity

For a record targeted at a lot, compare its possible business time with the lot's active interval:

- `valid`: every possible date is within the interval;
- `invalid`: no possible date is within the interval;
- `indeterminate`: the date is unknown or the possible range only partly overlaps the interval.

Invalid records are rejected unless the lifecycle history is corrected first. Indeterminate records may be retained when the gardener explicitly identifies the lot, but they do not determine historical placement, lifecycle transitions, or time-window calculations. A historical placement is shown only when every possible date resolves to the same placement; otherwise the placement remains unknown or ambiguous. Later correction can resolve the uncertainty.

### 3.4 Spaces

- Reparenting a space changes its parent placement from an effective business time. It does not rewrite earlier paths.
- A space and all of its ancestors belong to the same garden. Parent cycles are invalid.
- A lot continues to reference the same space identity when that space is renamed or reparented.
- Historical views resolve the space name, type, and parent path as they were effective at the event time when that time identifies one placement. Otherwise they show the placement as unknown or ambiguous while also allowing access to the space's current description.
- Reparenting an occupied space is allowed. Moving the space is not the same as moving each contained crop lot.
- A space cannot be archived while it or any descendant contains an active crop lot or an unarchived child space. Reparenting or ending those dependants first makes the operation explicit.
- An archived space cannot receive new current placements or new journal entries. Existing history is retained.

### 3.5 Tasks and task completion

| State | Meaning |
| --- | --- |
| `open` | No fulfillment is recorded, or an earlier fulfillment was undone. |
| `partially_completed` | At least one current intervention fulfills the task and the gardener has explicitly kept a remainder open. |
| `completed` | The gardener states that no intended remainder is open. |
| `cancelled` | No further work is intended; existing fulfillment interventions remain factual records. |

Completing all or part of a task atomically creates one linked intervention. The completion command has a stable idempotency identity, so retrying it locally or through synchronization returns the same intervention rather than creating another.

When task extent is known in a compatible unit, partial completions may account for that extent. When it is unknown, the gardener explicitly chooses “keep task open” or “complete task”; Hortinis does not invent a percentage. Several partial completions may therefore create several interventions.

Undoing a task completion reopens or recalculates the task and voids the intervention created by that particular completion. The intervention and its revisions remain accessible. An independently created intervention can be linked to a task, but unlinking it does not void the factual intervention.

### 3.6 Corrections, calculations, and synchronization

Correctable current values include business time, quantity, notes, action kind, targets, task-fulfillment details, loss reason, and lifecycle outcome. A revision records at least the stable record identity, predecessor revision, replacement values, correction time, and optional reason. The application presents the latest accepted revision as current and offers the full revision chain.

Relationship corrections must preserve invariants: the garden boundary, acyclic space and lot graphs, valid active intervals, and valid event targets. A correction to split quantities retains the parent and child identities. A proposed relationship correction that would orphan dependent history or make the graph invalid is rejected; the gardener must first retarget the dependent records or use explicit compensating events.

Current journal views and derived totals use the latest non-voided revision. Applying or undoing a correction recomputes affected plan realization, quantities, harvest totals, task status, and cycle status. Historical/audit views can reproduce every accepted revision and show when each was recorded.

For synchronized records:

- a correction is a new domain operation against an expected record revision and has its own stable idempotency identity;
- retrying the same correction cannot create another revision;
- corrections from different devices based on the same predecessor are concurrent, not a linear correction chain;
- compatible concurrent changes may merge only under an explicit business rule;
- contradictory current values produce an explicit conflict while retaining the last accepted current value and both proposals;
- resolving a conflict creates a new revision that references the competing proposals; it never deletes either proposal.

## 4. Worked example: one tomato cycle

Camille plans 12 `Marmande` tomato plants for 1–7 March without a final location. Publishing the plan changes it to `planned` but creates no actual cultivation.

On 3 March, sowing 12 seeds in `Porch nursery` creates a linked `establishing` cycle and active root lot `L1`; the planned period and actual sowing date remain separate. A later observation records 10 viable plants and two losses without converting seed and plant quantities.

At transplanting, `L1` is split into four plants in `Greenhouse` (`L2`) and six in `South raised bed` (`L3`). The cycle becomes `active`, and 10 comparable plants are allocated to the plan. Camille abandons the unallocated remainder, leaving the realized cycle and its history intact.

Watering before the split is inherited by both descendants but counted once in the cycle journal. Later interventions and harvests target the applicable child. Corrections change current projections while retaining prior revisions; completing all leaf lots completes and permits archiving of the cycle.

Camille records several harvests: 3 kg and 2 kg from `L2`, and an unknown quantity followed by 4 kg from `L3`. The known cycle total is 9 kg and the journal also states that one additional harvest has unknown quantity. The unknown value is not treated as zero.

The first `L2` harvest was entered incorrectly. Camille corrects it from 3 kg to 2.7 kg. Current views show a known total of 8.7 kg plus one harvest of unknown quantity. The harvest's history still shows the original 3 kg value, its recorded time, and the later correction time.

Camille completes `L2`; the cycle remains active because `L3` is active. After the last plants in `L3` finish, Camille completes `L3`. With every leaf lot completed, the cycle derives `completed`. Camille then archives the cycle to remove it from current-crop views without losing the plan comparison, lineage, location history, interventions, harvests, or correction history.

## 5. Acceptance scenarios

The scenarios are behavioral contracts for later domain tests and API examples. Identifiers are illustrative stable identities.

### AC-DM-01 — A plan does not create actual cultivation

**Given** a draft plan for 12 tomato plants with an intended sowing range and no location  
**When** the gardener publishes the plan  
**Then** the plan is `planned`  
**And** no crop cycle, crop lot, actual date, or actual location is created.

### AC-DM-02 — Sowing creates a linked actual cycle

**Given** the planned tomato cultivation  
**When** the gardener records sowing on 3 March in a nursery space  
**Then** one linked crop cycle and one active root lot are created  
**And** the actual sowing date and nursery placement are retained separately from the planned range.

### AC-DM-03 — Unknown location and quantity are valid

**Given** a gardener records an already-existing tomato crop  
**When** its original sowing date, current location, and quantity are unknown  
**Then** the cycle and active lot are accepted without fabricated values  
**And** the missing context is visible as unknown.

### AC-DM-04 — A complete split closes the parent

**Given** active lot `L1` contains 10 plants in compatible known units  
**When** it is split into `L2` with four plants and `L3` with six plants  
**Then** `L1` becomes `split` and cannot receive later-dated events
**And** `L2` and `L3` are active children in the same cycle  
**And** no active remainder remains on `L1`.

### AC-DM-05 — A partial move is represented by a split

**Given** one active lot contains 10 plants in the nursery  
**When** four plants move to a greenhouse and six remain in the nursery  
**Then** the parent is split into a four-plant greenhouse child and a six-plant nursery child  
**And** each child can evolve independently.

### AC-DM-06 — A complete move preserves lot identity

**Given** all four plants of active lot `L2` move from the greenhouse to a raised bed  
**When** the move is recorded  
**Then** `L2` keeps its identity and lineage  
**And** its greenhouse placement ends when its raised-bed placement starts  
**And** historical entries still show the location effective when they occurred.

### AC-DM-07 — Shared history is visible but not duplicated

**Given** one watering targets `L1` before it splits into `L2` and `L3`  
**When** the gardener views either child  
**Then** the watering appears as inherited pre-split history  
**And when** the gardener views or totals the complete cycle  
**Then** that watering contributes once by its stable journal-record identity.

### AC-DM-08 — Partial completion leaves the cycle active

**Given** `L2` and `L3` are the active leaf lots of one cycle  
**When** `L2` is completed  
**Then** `L2` is terminal and rejects later-dated work  
**And** the cycle remains `active` because `L3` is active.

### AC-DM-09 — Closing all leaf lots completes the cycle

**Given** every leaf lot except `L3` is completed  
**When** `L3` is completed normally  
**Then** the cycle derives `completed`  
**And** no harvest or other unrecorded action is inferred.

### AC-DM-10 — Harvest totals retain unknowns

**Given** a cycle has harvests of 3 kg, 2 kg, unknown quantity, and 4 kg  
**When** its harvest summary is calculated  
**Then** the summary reports 9 kg known  
**And** separately reports one harvest with unknown quantity  
**And** does not report the complete harvest as exactly 9 kg.

### AC-DM-11 — A correction changes the current projection, not history

**Given** a recorded harvest of 3 kg  
**When** the gardener corrects it to 2.7 kg  
**Then** current journal and harvest totals use 2.7 kg  
**And** the stable harvest identity is unchanged  
**And** the 3 kg revision, its recorded time, and the correction time remain accessible.

### AC-DM-12 — An accidental duplicate is voided, not deleted

**Given** two harvest records describe the same actual harvest  
**When** the gardener marks the second as a duplicate of the first  
**Then** the second is voided and excluded from current totals  
**And** both records and the duplicate relationship remain in correction history.

### AC-DM-13 — Task completion is idempotent

**Given** an open task to stake `L2`  
**When** the same completion command is applied repeatedly or synchronized after a lost acknowledgement  
**Then** exactly one linked staking intervention exists  
**And** every retry returns the same result.

### AC-DM-14 — Partial task work remains explicit

**Given** a task to stake 10 plants  
**When** the gardener records completion for four plants and keeps the remainder open  
**Then** one four-plant intervention is linked to the task  
**And** the task is `partially_completed` with six plants remaining  
**And** no work on the other six plants is inferred.

### AC-DM-15 — Undoing completion preserves the intervention audit

**Given** completing a task created a linked intervention  
**When** the gardener undoes that completion  
**Then** the task is reopened or recalculated from its other fulfillments  
**And** that completion's intervention is voided  
**And** the intervention and undo revision remain accessible.

### AC-DM-16 — Space reorganization preserves historical meaning

**Given** a greenhouse was under `Back garden` when an intervention occurred  
**When** the greenhouse is later renamed and reparented under `Sheltered area`  
**Then** the lot still references the same greenhouse identity  
**And** the intervention can show the former name and path effective on its business date  
**And** the current space view shows the new name and path.

### AC-DM-17 — An occupied space cannot be archived

**Given** a space or one of its descendants contains an active crop lot  
**When** the gardener attempts to archive the space  
**Then** the operation is rejected with the active dependants identified  
**And** no lot is implicitly moved, completed, abandoned, or archived.

### AC-DM-18 — Concurrent contradictory corrections become a conflict

**Given** two offline devices share the same 3 kg harvest revision  
**When** one corrects it to 2.7 kg and the other corrects it to 3.2 kg  
**And** both synchronize  
**Then** neither proposal silently overwrites the other  
**And** the accepted value and both proposals remain available  
**And** the gardener's resolution creates a new revision referencing both proposals.

### AC-DM-19 — A delayed entry uses business time for validity

**Given** a lot was active on 10 June and completed on 20 June  
**When** on 25 June the gardener records an intervention that occurred on 10 June  
**Then** the intervention is accepted with a recorded time of 25 June and business date of 10 June  
**And** the lot remains completed  
**But when** the intervention's exact business date is 21 June  
**Then** it is rejected unless the lifecycle history is corrected first.

### AC-DM-20 — Archiving changes visibility only

**Given** a completed tomato cycle  
**When** the gardener archives it  
**Then** it leaves current-crop views  
**And** its plan link, lot lineage, space history, journal, quantities, and correction history remain retrievable and exportable.

### AC-DM-21 — Partial plan realization retains the abandoned remainder

**Given** a plan targets 12 tomato plants  
**When** 10 comparable plants are allocated to its linked actual cycle  
**Then** the plan is `partially_realized` with a remainder of two plants  
**And when** the gardener abandons that remainder  
**Then** the plan is `abandoned`  
**And** the allocation of 10 plants and the linked cycle remain unchanged.

### AC-DM-22 — Partial lot completion first creates separate lots

**Given** one active lot contains 10 plants that share one location and lifecycle  
**When** cultivation ends normally for four plants while six plants remain active  
**Then** the parent becomes `split`  
**And** a four-plant child is created and completed  
**And** a six-plant child remains active  
**And** the cycle remains `active`.

## 6. Consequences for later design

The physical data model and HTTP/JSON contracts must preserve stable identities, revision chains, lot lineage, time-bounded placements, plan allocations, business-time precision, unknown quantities, void states, task-to-intervention fulfillment, and idempotent domain operations. They must not flatten these distinctions into one mutable “crop” record or duplicate ancestor events onto descendant lots.

API resource shapes, persistence layout, conflict-presentation details, and retention periods remain later technical design work. They must implement these behaviors and the synchronization guarantees in [ADR-0010](../architecture/decisions/0010-synchronization-protocol-model.md) without introducing transport or persistence concerns into the domain.
