# P0.7a — Local catalog validation contract

- Status: validated
- Depends on: P0.1
- Upstream: [`hortinis-plants`](../../../hortinis-plants/docs/development/implementation-plan.md)
- Distribution basis: [ADR-0014](../architecture/decisions/0014-plant-catalog-distribution.md)

## Purpose

This contract defines the small, non-production catalog profile used by the Hortinis V0 local-validation
track. It is the consumer-side handoff to `hortinis-plants` V1.2 through V1.4. It does not define the
complete France catalog, a production release, or the recommendation lifecycle.

The catalog project remains authoritative for the language-neutral schemas and generated artifact. Hortinis
pins compatible schemas and fixtures when V0.1 is implemented; it does not redefine catalog records in its
own domain model.

## Profile

The profile is named `dev-validation`.

- It is non-publishable and makes no `fr-mvp` completeness claim.
- It uses the same manifest, versioned schemas, canonical JSON Lines, gzip, SHA-256, byte-size, entry-count,
  source, licence and attribution contracts as a production artifact.
- A V0 client accepts an explicitly selected flat set of artifact files. A ZIP or other aggregate container
  is not part of this contract.
- The selected artifact is trusted because the operator explicitly selected it. Hashes provide integrity
  relative to that selection; they do not establish upstream rights or authenticity by themselves.
- The artifact must expose its catalog version, schema version, profile, generation time and minimum
  consumer version through the manifest.

The complete generated artifact remains outside the Hortinis repository. The repository tracks only pinned
schemas and small consumer conformance fixtures when V0.1 is implemented.

## Representative validation content

The initial validation subset is scenario-driven rather than a claim of broad catalog coverage. It contains
one generic plant concept representative of the first planning test, initially tomato, with:

- a stable plant-concept identifier and its taxonomic reference;
- at least one reviewed French or English preferred name and its evidence;
- one applicable outdoor or unheated-shelter cultivation context for metropolitan France;
- one reviewed sowing or planting rule using a supported calendar-date or relative-day window;
- the source, licence, attribution and review records required by every included assertion; and
- enough explicit scope to distinguish a generic plant rule from any optional cultivar-specific rule.

The subset may include an optional tomato cultivar exemplar only when the validation scenario requires it.
Cultivar evidence must remain cultivar-scoped and must never be promoted to the generic plant concept.
Additional plants, rules or contexts require a documented fixture-version update; they do not silently
expand the profile.

## Required consumer fields

Every included catalog assertion must retain or resolve:

- stable opaque record identifier;
- source identifier, source release and source record identifier;
- source locator;
- original value when normalization occurred;
- licence reference and explicit profile-eligibility decision;
- review identifier and review state;
- catalog and schema versions;
- plant, context and rule references;
- geographic and growing-system applicability; and
- sufficient structured rule parameters for Hortinis to evaluate the rule without interpreting prose.

Hortinis owns garden context, current weather, rule evaluation, explanation and recommendation state. The
catalog does not publish a final recommendation.

## Version retention

A validation result or fixture must retain:

- catalog version;
- schema version;
- artifact manifest digest;
- rule identifier and rule-data version;
- fixture version; and
- the relevant garden, date and weather/context fixture identifiers.

This is required to reproduce or explain a result after the catalog changes. A catalog update must not
rewrite historical user labels or historical catalog references.

## Missing and retired references

- A missing catalog reference remains representable in user-owned data. It does not block a free-form plant
  entry or invalidate garden history.
- A missing reference produces a limitation or abstention when a recommendation requires catalog data.
- A retired reference remains addressable for historical records.
- A retired reference with an explicit replacement may be shown with that replacement for current lookup,
  but historical references are not silently rewritten.
- A retired reference without a replacement produces an explicit limitation or abstention.
- A rule referencing a missing, retired-without-replacement, or ineligible record is not evaluated.

## Consumer fixtures

The V0.1 handoff must include small fixtures for:

| Fixture class | Required behavior |
| --- | --- |
| Valid artifact | Manifest, schemas, entries and references validate and can be staged. |
| Invalid entry | Schema validation fails with a stable diagnostic. |
| Corrupt hash | Integrity validation rejects the affected artifact. |
| Incomplete artifact | Missing required artifact prevents activation. |
| Unsupported version | Compatibility validation rejects the artifact. |
| Missing reference | Historical data remains usable; dependent advice is limited or abstains. |
| Retired reference | Historical data remains usable and replacement behavior is explicit. |
| Positive rule example | The selected rule is applicable and produces the reviewed expected result. |
| Limitation example | Missing or insufficient context produces an explicit limitation. |
| Abstention example | Missing required evidence or context produces no unsupported recommendation. |

Recommendation fixtures describe inputs, expected result, explanation factors, limitations or abstention,
and the catalog/rule versions used. They do not encode the complete recommendation lifecycle.

## Expected validation examples

The first consumer handoff must include these reviewed examples for the representative tomato rule:

| Example | Context | Expected result |
| --- | --- | --- |
| Applicable window | A generic tomato selection, an applicable outdoor or unheated-shelter context, and a date inside the rule window | A recommendation referencing the plant, context, rule, evidence and retained versions. |
| Incomplete context | The same selection without the geographic or growing-system context required to establish applicability | A limitation identifying the missing context; no national or shelter-specific conclusion is invented. |
| Unevaluable rule | The selected plant has no eligible rule for the supplied context, or the only candidate is ineligible, missing or retired without replacement | Abstention identifying why no supported recommendation can be produced. |

The positive example must expose the rule parameters and evidence that explain the result. The limitation
and abstention examples must preserve the selected plant and historical label while making the missing,
inapplicable or ineligible catalog information explicit.

## Acceptance

P0.7a is complete when `hortinis-plants` can use this document as the V1.2 curation input and V1.4 can
provide the generated `dev-validation` artifact, pinned schemas and small conformance fixtures. The
consumer verification and activation pipeline must be defined over a source-independent path-to-bytes
boundary so a later authenticated HTTPS source can use the same path.

HTTPS acquisition, release discovery, update polling, weather-provider selection and the full recommendation
lifecycle remain outside P0.7a.
