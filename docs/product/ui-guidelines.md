# Hortinis UI guidelines

- Updated on: 2026-09-12.
- Status: draft product direction; accepted interaction choices are distinguished from unresolved details.
- Related documents: [home-screen specification](home-screen-specification.md), [functional decisions](functional-decisions.md), and [domain model](domain-model.md).
- These documents define user experience, not implementation technology. Product implementation remains gated by the [foundation readiness plan](../development/foundation-implementation-plan.md#foundation-readiness-gate).

## Product experience

Hortinis helps a gardener plan ahead, know what to do as the season progresses, record what happened, and use prior seasons' information to make later guidance more useful. It should ultimately serve gardeners and small gardening professionals. The maintainer is the first user; professional-specific needs are not thereby part of the MVP.

Phone is the primary device for everyday use, including offline garden work. The same essential capabilities should work on a computer; planning should be comfortable on either. Accessibility is a product requirement. Quality targets, supported devices, and conformance criteria remain open under O-11.

## Decisions from the UI exploration

| ID | Decision | Rationale and boundary |
| --- | --- | --- |
| UI-01 | Make the opening view an actionable overview of one selected garden. | The user should quickly see what needs doing and where. Other gardens may show an attention indicator without obscuring the selected context. |
| UI-02 | Group work by place, including garden-wide work and work in empty spaces. | Gardeners orient themselves spatially; not every action belongs to a crop. |
| UI-03 | Keep tasks, recommendations, planning, and journal entries conceptually distinct. | Advice may be explicitly turned into a task. Reading advice or creating a task does not record performed work. Planning is always reachable. |
| UI-04 | Allow garden use without drawing a layout. | A map or space structure can be added when useful; neither is a prerequisite for recording or planning. |
| UI-05 | Keep a labeled **Record** action in the bottom navigation. | This placement was preferred to a floating button or a top-bar action and should remain easy to reach. Final label and desktop behavior remain open. |
| UI-06 | Keep period selection and daily weather discreet and secondary. | They support decisions but must not dominate the home view. Details open on demand. |
| UI-07 | Use a balanced task density with visible place groups. | Sparse concepts showed too few tasks; the densest concept felt crowded. Place grouping is preferred over a flat task list. |
| UI-08 | Keep garden configuration optional at first use and minimize setup questions. | A gardener should be able to start quickly, either by creating a garden or by deferring configuration. |
| UI-09 | Adapt gardening guidance and application help independently. | Gardening knowledge does not predict software familiarity. Beginners should see relevant information; experienced users can request greater precision. Do not lock features by inferred level. |
| UI-10 | Support quick task completion with optional detail entry. | A gardener can record completion without a mandatory follow-up question; details, correction, and undo remain accessible. |
| UI-11 | Keep a completed task and its completion control in the same place during consecutive actions. | Stable positions reduce repeated aiming and prevent list movement under the user's finger. The retained prototype marks completion without removing or reordering that row. |
| UI-12 | Use compact, dismissible floating feedback after completion, with **Details** and **Undo**. | The confirmation should not expand the list or obscure its completion controls. Details also remain accessible from the completed row and journal. This interaction is provisional pending accessibility and device validation. |
| UI-13 | Let quick recording grow into action-specific detail without blocking a simple entry. | Start with a suitable action and target; provide optional fields when wanted. Minimum valid content and recovery behavior need specification before implementation. |
| UI-14 | Plan for garden history through time and seasonal reuse. | Past, current, and planned placements have distinct meanings. The detailed map and its release scope remain open; history should remain usable without a map. |

## Interaction and information principles

- Keep one selected garden clear. Garden identity must not be lost when switching to another garden or inspecting its attention indicator.
- Preserve original dates and uncertainty. An overdue task keeps its due date and appears within its place group with clear text emphasis.
- Show recommendations separately from tasks. Conversion is an explicit user action; viewing or converting advice does not create a journal event.
- Let users record work against a crop, a space, or the whole garden. Broad work may include exclusions, such as watering everywhere except a greenhouse; coverage, history, and duplicate prevention require further specification.
- Let the user record garden- or space-level work such as soil preparation or fertilizing an empty bed.
- Keep a quick path for harvests, watering, observations, and other work. A user may add optional details after choosing to record; do not require quantity when it is unknown or a diagnosis for an observation.
- Weather, time-period selection, and planning indicators are supporting controls. Do not let them crowd out the garden's work.
- Use text labels alongside color and icons for task state, alerts, and uncertainty.
- Keep all functions available across experience levels. Preferences may change explanation depth and visible detail; their defaults, scope, and storage are undecided.

## Mapping direction

Mapping is an important future capability, not a settled MVP requirement. It should support a garden through time: crop positions in the past, present, and future; work associated with locations; an eventual sense of capacity; and later exploration of rotation and companion planting. Both a simple approach for beginners and precise editing for experienced users should coexist. The application must remain complete without a map. No geometry, capacity calculation, or horticultural suitability rule is selected here.

## Reference concepts

The accepted starting layout is mockup M; completion feedback and stable task rows use provisional mockup O. The detailed decision record and links to the renderings are in the [home-screen specification](home-screen-specification.md). Earlier directions are retained in the UI exploration directory for comparison. These are design prototypes, not implemented screens or approval to begin business-feature development.
