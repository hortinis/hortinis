# Hortinis home-screen specification

- Updated on: 2026-09-12.
- Status: draft. M is the accepted layout starting point; O is the provisional completion interaction reference.
- Parent: [UI guidelines](ui-guidelines.md). Domain behavior remains governed by the [domain model](domain-model.md) and [functional decisions](functional-decisions.md).
- Implementation readiness: contributes to P0.2; does not complete that milestone or bypass foundation readiness.

## Purpose

On opening Hortinis, a gardener should understand what needs attention in the selected garden, see where the work belongs, and be able to record actual work quickly. Planning remains reachable. Weather and period controls support these activities without taking focus away from them.

## Reference mockups

**M** is the accepted starting layout: garden and attention context, grouped place sections, compact weather and period controls, and labeled Record in the bottom navigation. **O** explores what happens after a task is completed: its row remains in place, and a compact floating confirmation offers Details and Undo.

- [Open mockup O: stable task rows and floating completion feedback](</home/julien/.codex/visualizations/2026/09/08/01a08211-6f83-75b0-9f59-4eb6d04cff03/stable-completion-fr.html>) — interactive discussion prototype; French sample UI, temporary in-memory data.
- Mockups A–M and O are design explorations, not production UI. The French copy is only for the requested prototype; repository specifications remain English.

The functional outline is:

```text
Selected garden                         Other-garden attention, if any
Relevant urgent concern, when present
Period summary                          Remaining task count

Garden-level work
  Task rows
Space or bed
  Task rows
Other relevant places
  Task rows

Compact period control                  Compact daily-weather indicator
Home        Garden        Record        Plan        Journal
```

This is information hierarchy, not fixed geometry. Exact navigation routes, labels, icons, and responsive layout remain open.

## Accepted behavior and provisional interaction

| Topic | Direction | Status |
| --- | --- | --- |
| Selected garden | Show one garden as the active work context; signal attention elsewhere without mixing garden targets. | Accepted direction; indicator rules open. |
| Task organization | Group by place, including garden-wide actions and empty spaces. | Accepted direction; nested and multi-place cases open. |
| Overdue tasks | Keep them in their place group, visibly mark overdue, and retain their original dates. | Accepted. |
| Recommendations | Keep distinct from tasks; let the gardener explicitly turn one into a task. | Accepted; conversion lifecycle open under O-07. |
| Planning | Keep it reachable while work is under way; surface an opportunity discreetly. | Accepted direction; trigger rules open. |
| Record action | Labeled Record action in bottom navigation, preferred over floating or top-bar placement. | Accepted direction; desktop placement and final label open. |
| Period and weather | Compact secondary indicators with detail on demand. | Accepted direction; period choices/weather fields open. |
| Completing a task | Keep checked rows and controls in place during consecutive completions; visibly update task state and remaining count. | O retained provisionally; persistence after leaving/reloading the screen needs a product rule. |
| Completion feedback | Compact dismissible floating feedback with Details and Undo; it should not reflow the list or cover completion controls. Keep a path to details from the completed row and journal. | O retained provisionally; accessibility and device behavior require validation. |
| Optional details | Do not force a detail form after completion. Editing a later detail should revise the same intervention, not create duplicate work. | Accepted direction; operation and failure handling governed by domain rules and P0.4. |
| Mapping | Useful in the future, including past/present/future placement, but never required for the application to work. | Accepted direction; detailed scope and MVP placement open. |

## Quick-recording direction

The following choices advance the direct **Record** journey but do not complete O-04:

| Topic | Direction | Status |
| --- | --- | --- |
| First choice | Start with the record or action type. Keep every available type visible in this same initial view without experience-based or context-dependent access. | Accepted direction; exact type set, labels, grouping, ordering, and small-screen density require testing. |
| Default target | A more specific target is optional. If none is chosen, the entry targets the currently selected garden. | Accepted. A targetless harvest in the interface conflicts with the current crop-target requirement in the domain specification and must be reconciled before acceptance criteria are finalized. |
| Crop and space targeting | Let the gardener select a crop or a space. A crop selection covers the whole selected crop by default; an optional space refinement limits it to the applicable lot portion in that space. Spaces remain selectable when empty. | Accepted direction; resolving user-facing crops to cycles and lots, ambiguous placements, and multi-lot history remains open. |
| Business time | Prefill today, allow immediate recording, and keep both editing and an explicit unknown value available. | Accepted direction; approximate dates and ranges in the quick path remain open. |
| Type-specific minimum | Ask only for content required by the selected record type. Keep quantity and diagnosis optional. Retain one general Observation entry for now rather than a separate pest entry. | Accepted direction; concise observation entry and other type-specific validation require scenario review. |
| Optional details | Keep optional detail fields out of the mandatory path. Recording first and adding or correcting details later must revise the same record. | Accepted direction; feedback placement and accessibility remain to be tested. |
| Draft closure | Explicitly closing the recording surface discards the unsubmitted draft. | Accepted direction; back navigation, garden switching, accidental closure, and failure-state behavior remain open. |
| Exclusions | Do not include broad-action exclusions in the MVP quick-recording journey, but do not preclude later coverage and exclusions. | Accepted scope boundary; later semantics remain open. |
| Layout independence | Recording against a garden, crop, empty space, or occupied space does not require a drawn or dimensioned plan. A future plan may record space dimensions without creating the space's identity. | Accepted. |
| Post-record feedback | Reuse O's compact **Details** and **Undo** pattern as a candidate for direct recording. | Provisional; retain only after interaction, accessibility, and device testing. |

This work remains **in progress**. The retained discussion prototype exercises the choices above but is not a repository artifact, a final interaction, or implementation authorization.

## Questions remaining for O-04

The following decisions are not settled by M or O:

1. **Quick recording:** What exact types, labels, grouping, and order keep every type discoverable without overwhelming a phone screen? How should a garden-targeted harvest be represented or reconciled with the current domain requirement for a crop target? How does crop-plus-space selection resolve whole cycles, several lots, historical placements, and ambiguity without exposing internal terminology?
2. **Plans, tasks, and actual work:** How should the home and journal expose relationships between plans, due work, completed work, and actual interventions without implying that planned work happened?
3. **Navigation and journal:** What are the final destinations, journal filters, and access paths to observations, harvests, interventions, and corrections?
4. **Periods:** What periods are available, what does “this week” mean, and how are overdue, undated, approximate, and interval-dated tasks included and sorted?
5. **Completion row lifecycle:** Do completed rows stay visible for the selected period, and for how long? How does O's stable-row behavior work after navigation, reload, sync, Undo, and larger text?
6. **Other-garden indicator:** What qualifies as attention, how is urgency conveyed, and must the gardener confirm before switching gardens when doing so discards an unfinished recording form?
7. **Weather and warnings:** Which minimal daily weather fields appear, how are freshness and provider absence explained, and how do current warnings coexist with another selected period?
8. **Preferences:** Which of the four distinct dimensions are in MVP, what are defaults, how can the user explicitly change them, and are settings per gardener or per device?
9. **Planning prompt:** When should the app discreetly suggest planning mode, and how does the gardener dismiss or revisit the prompt?
10. **Mapping boundary:** Which list/map transitions and historical placement views are needed in the MVP, if any? The map editor, capacity, rotation, and companion-planting rules are not selected.

P0.3/O-11 also needs measurable decisions for accessibility, supported browsers and devices, field use, volumes, local-write latency, and storage/migration failure states. These criteria apply to all screens, including O.

Outside O-04, O-05 through O-07 retain decisions about catalog scope, weather provider and privacy, and recommendation rules/lifecycle. O-08 through O-10 retain account access and synchronization, backup/restoration, and data continuity decisions. See the [open questions register](open-questions.md).

## Expected scenarios for later acceptance criteria

These are specification scenarios, not evidence of implementation or validation:

- Open the app offline and record work without creating a layout or filling in optional configuration.
- See an overdue task inside its own place group with its original date.
- Complete several tasks and confirm that controls stay in place while the remaining count changes.
- Add details after completion, correct them later, or undo completion without duplicating an intervention.
- Convert a recommendation to a task without implying that work was done.
- Record against the selected garden by omitting a more specific target, and verify that the entry never becomes unowned or associated with another garden.
- Select a crop, optionally narrow it to the lot portion in one space, and inspect its history without duplicated records.
- Close an unfinished recording surface and confirm that its draft is discarded.
- Change periods without rewriting dates or hiding relevant overdue and undated work.
- Enlarge text and use assistive technology without losing garden context, task state, or access to Record.

## Link to the implementation plan

This exploration and specification work are explicitly expected under [P0.2 — Resolve the MVP experience](../development/product-implementation-plan.md#p02-resolve-the-mvp-experience-o-04): navigation, active garden, home view, quick recording, journal/history, planned-versus-actual presentation, and offline/sync states. This work materially advances P0.2, but the unresolved items above mean it is not complete.

It contributes to **P0.1** by making accepted directions and open questions traceable, but P0.1 is not complete until all MVP decisions and domain scenarios map to increments. The completion interaction touches **P0.4** (task fulfillment, intervention revision, and undo semantics), but does not replace the implementation-neutral operation and record specification. Accessibility and field usability need the measurable quality envelope in **P0.3/O-11**. No product code is authorized before the technical foundation gate passes.
