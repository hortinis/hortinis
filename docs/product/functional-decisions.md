# Hortinis — Functional decisions and MVP scope

- Consolidated on: 2026-09-05.
- Status: product framing based on the supplied reports and explicit decisions made during the discussion.
- Purpose: support continuation without rereading the conversation; this is not yet a detailed specification or a delivery schedule.
- Related documents: [product direction](product-direction.md), [domain glossary and lifecycle rules](domain-model.md), and [open questions and design handoff](open-questions.md).

## 1. Interpretation and authority

The decisions recorded below supersede conflicting proposals in the earlier reports. Vision and UX principles from those reports are distinguished from detailed MVP decisions. Decision identifiers are retained for traceability.

The product name is **Hortinis**; Hortisys is the former name used in some sources. The name and license are established by [ADR-0016](../architecture/decisions/0016-project-name-and-license.md).

The [existing ADRs](../architecture/README.md) remain the technical reference. This document selects no framework, protocol, or provider. Any work needed to reconcile product requirements with the ADRs is identified in the open questions without canceling accepted product decisions.

## 2. Consolidated vision and principles

Hortinis is open-source garden-management software primarily intended for amateur, regular, and experienced gardeners. Its ambition is to become **the garden's memory and a decision-support tool**.

The value loop is:

**Understand the garden → Plan → Act → Observe → Remember → Learn → Adapt the next steps.**

This is not a mandatory sequence: observations, interventions, and harvests can be recorded without prior planning.

The following principles from the initial framing are retained:

- core use works offline; the network supplies additional data and synchronization;
- data entry is quick, contextual, and progressive, with few required fields;
- unknown information can be represented without inventing values;
- general horticultural knowledge is distinct from knowledge of the actual garden;
- recorded facts, inferences, proposals, and gardener decisions remain distinguishable;
- recommendations are explainable and initially deterministic; users can ignore, postpone, or challenge them;
- the garden's memory can be preserved and recovered;
- one application can vary guidance and interface depth without locking features behind an experience level.

These principles do not make every idea in the reports mandatory for the MVP. Proposed personas, priority scores, and metrics are neither validated research findings nor adopted algorithms.

## 3. MVP decisions

### DF-01 — First-release scope

The MVP combines **garden memory, simple planning, and initial explainable recommendations**.

It covers gardens and spaces, cultivation plans and actual crop lots, tasks, interventions, observations, harvests, journal and history, a minimal local catalog, weather data needed for the two recommendations, offline operation, synchronization, complete backup, and restoration.

Weather was initially requested if feasible and subsequently made concrete through the two cases in DF-08. It belongs to the selected scope for those uses; it does not turn Hortinis into a general weather service. Exact coverage remains to be documented.

### DF-02 — Typed spaces and optional nesting

A garden contains spaces with a type. Spaces can contain other spaces without imposing a zone/plot/bed hierarchy.

A crop can be placed in a space without creating every intermediate level. Specialized business behavior for space types will be added only if needed.

The type list, movement rules, and nesting limits remain open. This decision does not require advanced geometric mapping.

### DF-03 — Crop lots and shared origin

Tracking uses **one crop lot per location**. When a lot is split across locations, the resulting lots retain their shared origin and can then evolve independently.

Shared history must remain accessible without duplicating interventions or harvests. The exact representation of the original lot after splitting and of remaining quantities is still to be specified.

Perennials and trees are outside the MVP, but future support must remain possible. The model must therefore not permanently equate a crop with a calendar year.

### DF-04 — Plans and partial execution

A selected crop can be planned before its location or date is known.

Planned and actual information is retained separately, with links between the cultivation plan and actual lots. Partial execution allows the remainder to be postponed or abandoned.

An actual date does not replace its planned date. An observed growth stage remains distinct from a software estimate. The terms plan and lot describe functional responsibilities here, not an agreed database schema.

### DF-05 — Tasks and interventions

A task describes an action to perform. An intervention records an action performed and can exist without a prior task.

Marking a task as completed creates a linked intervention with prefilled information and an editable date defaulting to today. Additional details are requested only when needed. Retrying or synchronizing the same completion must not create multiple interventions.

Observations can be entered without a precise diagnosis. Harvest quantity can remain unknown; weighing is not mandatory, as established in the initial journeys.

### DF-06 — Corrections and archiving

The journal displays the corrected value of an intervention or harvest. Previous values remain accessible through correction history.

Crops can be archived to leave current views while retaining their history. Selective permanent deletion is deferred beyond the MVP.

This decision concerns crop archiving. It does not implicitly settle undoing erroneous entries, erasing all data, technical logs, or retention policies.

### DF-07 — Initial catalog and free-form entries

The initial catalog contains a small set of common vegetables and herbs, with documented data for the two selected recommendations.

A plant or variety missing from the catalog can be entered under a free-form name and used immediately. A catalog reference can be attached later. Advice is limited to available knowledge.

The canonical catalog remains external, versioned, and distinct from personal data. References use stable catalog identifiers, in accordance with [ADR-0005](../architecture/decisions/0005-external-plant-catalog.md) and [ADR-0014](../architecture/decisions/0014-plant-catalog-distribution.md).

A way to contribute to catalog enrichment is desired later. A personal entry does not automatically become a public contribution.

### DF-08 — Two complementary recommendations

| Selected case | Subject | Expected result |
| --- | --- | --- |
| Forecast cold risk | Existing crops | Indicate and explain the risk and invite the gardener to check protection |
| Sowing or planting window | Already selected crops | Help choose a period using the catalog and available weather conditions |

The second case must work even when location or date is unspecified, with explicit limitations. Automatically selecting new crops from the catalog is not included in the MVP.

Both cases are included. Validating them successively, first for existing crops and then for planning, is a proposed work sequence rather than a scope reduction.

Each recommendation must be able to explain its factors, missing or stale data, and uncertainty. Exact rules, thresholds, time windows, and confidence levels have not yet been selected.

For example, the absence of recorded watering does not prove that watering did not occur. Viewing or accepting a recommendation does not prove that an intervention took place either.

### DF-09 — France, climate, shelters, and location

France is the initial territory. Support for several climate contexts is desired; boundaries, actual territories covered, and reliability criteria remain to be determined.

The MVP covers outdoor growing and unheated shelters. Protection is specified when known. Indoor temperature is never automatically inferred from outdoor forecasts.

An approximate garden position should provide suitable data while respecting privacy. No precise-coordinate requirement has been adopted. Granularity and information sent to the provider remain to be designed.

General climate, local forecasts, and conditions specific to a space must remain distinct. Forecasts already available locally can be used offline according to their validity; stale or missing data can limit or suspend advice.

External providers remain optional adapters selected and enabled by the operator, in accordance with [ADR-0015](../architecture/decisions/0015-privacy-and-observability.md). The absence of a configured provider does not block core garden use.

### DF-10 — Alert presentation

Cold-risk alerts appear inside the application, on the home view and relevant crops. Notifications while the application is closed are deferred.

The MVP therefore does not promise to warn a gardener who does not open the application. Message count, ordering, and display duration remain to be specified.

### DF-11 — Synchronization during foundation work

Synchronization must be implemented and validated progressively from the technical foundations onward so its risks are tested throughout development. It is not deferred beyond the MVP.

It concerns the devices of one gardener. Sharing a garden between several people is deferred.

Business operations are recorded locally without waiting for the server. Connectivity returning allows configured synchronization to resume. Errors or conflicts must not block independent local use.

This progression respects the repository rule: validate the technical foundation before adding business features. Initial protocol validation can use technical test data.

### DF-12 — Configurable server access

| Situation | Access decision |
| --- | --- |
| Standalone local use | Start without an account |
| Self-hosted server for individual use | Account optional according to server configuration |
| Instance shared by multiple users | Accounts required |

Connecting to a server later must retain and incorporate data already created locally. Local identity therefore does not necessarily depend on a server account.

A shared instance hosts several users; it does not imply a collaborative garden. Access control for individual mode and isolation between accounts remain to be designed.

This product decision extends the need addressed by [ADR-0013](../architecture/decisions/0013-identity-and-storage-defaults.md), which provides built-in local accounts. Its technical integration must be formalized before implementation; an optional account does not itself select an access mechanism.

### DF-13 — Concurrent changes

Compatible changes are merged automatically. When changes contradict each other, both versions are preserved and the gardener chooses without blocking independent operations.

Compatibility is defined by business rules, not merely by different field names. Correction history records accepted changes; a conflict represents an unresolved disagreement.

Reconciliation rules must fit [ADR-0010](../architecture/decisions/0010-synchronization-protocol-model.md), without silent last-write-wins overwrites or bypassing revision and recovery guarantees.

### DF-14 — Backup and restoration

A manually exportable complete backup and its restoration are part of the MVP. A discreet reminder accompanies the absence of a recent backup. Its frequency is not yet defined.

A backup must recover business history: free-form entries, links between plans and lots, interventions, and correction history. The exact format, included references, and handling of synchronization state remain to be defined.

Backup restoration is distinct from importing data from another application, which is not committed for the MVP. Server-installation backup procedures also remain to be specified.

## 4. UX preferences: retained direction, open details

The supplied framing distinguishes horticultural experience, familiarity with the application, guidance needs, and interface depth. An explicit preference must take precedence over an estimate; settings work locally and no feature is locked by an experience level.

Defaults, screens, inference mechanisms, and the exact first-implementation scope were not settled during the questions in this discussion. The implementation prompt quoted in the sources is not authorization to start coding immediately.

Business events, synchronization history, and any usage measurements remain distinct. Retention or individual-inference metrics suggested in the sources must not be implemented in conflict with [ADR-0017](../architecture/decisions/0017-privacy-preserving-web-analytics.md).

## 5. Deferred capabilities and future ambition

Explicitly deferred during the decisions: perennials and trees, space specializations if needed, collaboration between gardeners, catalog contributions, notifications outside the application, and selective permanent deletion.

The reports also discuss rotations, companion planting, seasonal reviews, historical comparisons, photos, detailed mapping, diagnosis, IoT, and a conversational assistant. These prospects are neither an approved roadmap nor delivery commitments. Social networking, a marketplace, commerce, physical automation, and general-purpose AI were excluded from the initial MVP.

The durable objective is to improve gardener decisions using their history and explainable knowledge. The [product direction](product-direction.md) describes that ambition and exploratory ideas; [open questions](open-questions.md) record the remaining decisions.
