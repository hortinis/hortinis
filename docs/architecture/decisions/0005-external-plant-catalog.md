# ADR-0005: External plant catalog

- Status: Accepted

## Context

Plant reference data has its own lifecycle and can be reused independently of the application.

## Decision

Maintain the canonical plant catalog outside this repository. Hortisys consumes a published, versioned representation through an adapter. Application records refer to catalog entries with stable, opaque identifiers rather than names or positions.

User-owned garden data remains valid when catalog wording or classification changes. Imported catalog data is distinguishable from user-entered data.

## Consequences

- Identifier stability is part of the catalog contract.
- Catalog updates must support validation, version compatibility, and safe local migration.
- Missing or retired references remain representable.
- The application can retain a local catalog snapshot for offline use.
- `packages/plants` contains the integration boundary and mappings, not the canonical catalog source.

The publication format, transport, and update policy remain open decisions.
