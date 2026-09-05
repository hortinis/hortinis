# Hortinis — Product direction

- Updated on: 2026-09-05.
- Status: consolidated product vision with exploratory ideas; not a dated roadmap.
- Release commitments: [functional decisions and MVP scope](functional-decisions.md).
- Unresolved choices: [open questions and design handoff](open-questions.md).

## Purpose

This document describes where Hortinis is intended to go beyond its first release. It provides a reference for judging future ideas while leaving room for learning from gardeners and actual seasons.

The vision and principles below consolidate the supplied product analysis. Future outcomes, horizons, and ideas are proposals to validate. Inclusion here does not add a feature to the MVP or authorize implementation.

## Vision

**Help gardeners understand their garden, preserve its memory, and make better decisions over successive seasons.**

Hortinis should become a companion that connects what the gardener knows, what happened in the garden, and relevant horticultural knowledge. Its usefulness should grow as the garden's history develops, while remaining useful from the first recorded crop or action.

The gardener retains the final decision. The software explains what it knows, what it infers, and what remains uncertain.

## Problem and intended change

Garden knowledge is scattered across memory, notebooks, photographs, spreadsheets, and separate applications. Gardeners must connect places, crops, timing, interventions, observations, harvests, weather, and earlier results themselves.

Hortinis aims to make that knowledge retrievable and useful in context. A gardener should increasingly be able to answer:

- What is happening in my garden, and what deserves attention?
- What did I plan, and what actually happened?
- Why might this action be appropriate now?
- What did I learn last season that should change my next plan?

The initial audience is amateur, regular, and experienced gardeners. Differences in experience should primarily affect guidance and information depth. Extending the audience to professional production or collective management remains a strategic question.

## Value loop

**Understand → Plan → Act → Observe → Remember → Learn → Adapt.**

The garden is the shared context across these activities. Recording information supports later understanding; it must remain easy enough to do while gardening.

Users can enter the loop at any point. A harvest or observation should not require a complete plan, and incomplete data must not be presented as established knowledge.

## Three product pillars

| Pillar | Role | Example of value |
| --- | --- | --- |
| Memory | Preserve places, intentions, actual work, observations, and results | Retrieve what happened to a crop before and after its lot was split |
| Context | Connect that history to versioned horticultural knowledge and available environmental data | Explain what a forecast means for a known crop and its recorded protection |
| Decision support | Offer understandable options and help the gardener act on them | Suggest checking cold protection or choosing a sowing period, while exposing uncertainty |

Deterministic rules can deliver these capabilities. Learning from the garden means making better use of its history; it does not imply that machine learning is required.

## Durable principles

- Make core work available offline and preserve accepted local work through synchronization and recovery.
- Keep data entry minimal and reveal additional detail when useful.
- Preserve planned and actual information and make corrections understandable.
- Separate facts, inferences, recommendations, and user decisions.
- Prefer a small number of relevant, explainable recommendations over a large volume of messages.
- Permit correction, dismissal, postponement, and alternative choices.
- Keep user data recoverable and external providers replaceable and optional.
- Preserve autonomous self-hosting and the repository's privacy boundaries.
- Adapt guidance without locking features behind an experience level or commercial status.
- Add complexity only when it supports a demonstrated gardening need.

These principles apply to every horizon. The [architecture decisions](../architecture/README.md) remain authoritative for their technical realization.

## How the MVP tests the direction

The MVP establishes the first practical loop: represent a garden, plan selected crops, record real work, retain history, and receive two explainable recommendations.

Cold-risk guidance tests support for an existing crop. Sowing or planting windows test support for an intention that may not yet have a date or location. Progressive synchronization validation and complete backup/restoration test the reliability needed for long-term memory.

The [functional decisions](functional-decisions.md) define the exact scope. A successful MVP should establish whether gardeners can capture useful information with acceptable effort and understand the resulting advice. It does not by itself demonstrate improved yield or reliable explanations of crop failure.

## Outcomes to explore over several seasons

These are proposed outcomes, not measured results or delivery promises:

- A returning gardener can prepare a season using earlier plans, actual dates, observations, and harvests instead of reconstructing them from memory.
- The gardener can explain why a new plan differs from the previous one.
- A recommendation makes its supporting history and missing context understandable.
- Different levels of experience can use the same garden model with suitable guidance.
- Garden history remains usable when spaces change, crops split, devices are replaced, and reference data evolves.

The exact outcomes and evidence expected after two or three seasons remain to be agreed with the intended users.

## Exploratory horizons

| Horizon | Intended user outcome | Candidate capabilities | Evidence to seek before expanding |
| --- | --- | --- | --- |
| Reliable memory and first advice | Retrieve work and understand the two MVP recommendations | Fast recording, history, synchronization, restoration, targeted weather context | Usable field workflows, demonstrated recovery, understandable advice |
| Continuity between seasons | Use previous experience to prepare a better-informed next plan | Seasonal reviews, planned/actual comparison, reusable plans, rotation checks | Gardeners can point to history that changed a planning decision |
| More diverse gardens and richer context | Follow a wider range of real growing situations over time | Perennials, trees, contextual rules, richer reference data | Demonstrated needs and adequate knowledge for each extension |
| Selected extensions | Address a validated need beyond individual use | Collaboration, catalog contributions, optional integrations | User value and sustainable maintenance and operation |

These horizons have no dates or fixed delivery order. Perennials, rotations, or collaboration may be prioritized differently as evidence develops. Foundation validation remains a prerequisite for business-feature implementation.

## Candidate ideas and questions

| Idea to explore | Gardening need | Main question before commitment |
| --- | --- | --- |
| Reuse a previous season's plan | Start from experience rather than a blank page | How can intentions be copied without copying past actual events? |
| Rotation and succession guidance | Compare possible locations and crop sequences | Is space history sufficient, and which documented rules apply? |
| Staggered sowing | Plan several growing batches over time | Can timing and quantity help without pretending to predict harvest precisely? |
| Seasonal reviews | Find observations and differences worth acting on | How can the product distinguish patterns from unsupported causal explanations? |
| Perennials and trees | Preserve history beyond annual cycles | Which lifecycle and repeated-harvest concepts are needed? |
| More contextual advice | Make suggestions fit the actual garden | Which additional data improves decisions enough to justify entry effort? |
| Photos and richer observations | Capture change or a problem quickly | What storage, synchronization, and privacy costs are acceptable? |
| Catalog contributions | Improve shared reference coverage | How are provenance, licensing, review, and stable identifiers governed? |
| Collaborative gardens | Coordinate work between people | Which permissions, attribution, and conflict behaviors are required? |

Detailed maps, diagnosis assistance, sensors, and conversational interaction also appeared in the initial analysis. They remain lower-definition possibilities and need a concrete user problem before further design. A conversational interface must not replace traceable knowledge or turn uncertain advice into authoritative claims.

## Boundaries

The selected MVP excludes social networking, a marketplace, commerce, physical automation, and general-purpose AI. They are not adopted long-term priorities here either. A change in product positioning would require an explicit decision.

Hortinis should not grow into a general weather service. Weather supports gardening decisions. External accounts or proprietary services must not become hidden prerequisites for core use.

Broader country coverage, professional workflows, and a hosted-service strategy remain open choices rather than implied consequences of the vision. France is the first coverage target, not a validated global reference model.

## Choosing and reviewing the next step

For each candidate capability, describe the user situation, expected outcome, required data, prerequisites, uncertainty, maintenance burden, and a small way to test its value.

Prioritize according to user value, confidence in the need, dependencies, and sustainable cost. More data does not automatically create better horticultural advice.

Use voluntary field trials, interviews, reviewed recommendation examples, and permitted technical measurements. Do not claim that crop outcomes are caused by the software without supporting evidence. Any analytics must satisfy [ADR-0017](../architecture/decisions/0017-privacy-preserving-web-analytics.md); individual longitudinal tracking is not implicitly authorized by these product goals.

Proposed review points are foundation validation, MVP field trials, and feedback after a growing season. At each review:

1. Record what users achieved and where the loop failed.
2. Reassess the most important problem and available evidence.
3. Select the next outcome and the smallest useful scope.
4. Move accepted commitments into a release specification or functional decision.
5. Keep remaining ideas explicitly exploratory and update this direction if needed.

Detailed specifications should focus on the next deliverable horizon. The [open strategy questions](open-questions.md#5-long-term-strategy-questions) provide a starting point for the next discussion.
