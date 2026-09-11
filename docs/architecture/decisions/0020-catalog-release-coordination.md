# ADR-0020: Catalog release coordination

- Status: accepted

## Context

ADR-0005 and ADR-0014 leave catalog schema ownership and local acquisition details to coordination with the
external catalog project. P0.7a defines the smaller local-validation contract needed before Hortinis V0.1.

Hortinis must consume the upstream catalog contract without redefining it or treating a local validation
artifact as a production release.

## Decision

- `hortinis-plants` is authoritative for the language-neutral catalog schemas. Hortinis pins compatible
  generated schemas and conformance fixtures under `contracts/catalog/` when V0.1 is implemented.
- The non-publishable `dev-validation` profile may be used for V0. It follows the same manifest, schema,
  JSONL.gz, hashing and attribution contracts as a production artifact but makes no `fr-mvp` completeness
  claim.
- V0 local acquisition accepts an explicitly selected flat set of artifact files. A catalog source exposes
  those files through a path-to-bytes boundary shared by verification, integrity validation, staging and
  activation. A later HTTPS source uses the same boundary.
- Hortinis validates artifact contents with its pinned trusted schemas. Schemas included in a selected
  artifact are distribution material and are not dynamically trusted as validation authority.
- The complete generated catalog remains outside the Hortinis repository. Only pinned schemas and small
  valid or invalid consumer fixtures are committed.
- Artifact signing and key distribution are deferred; manifest hashes provide integrity relative to the
  explicitly selected local artifact or later authenticated HTTPS source.

## Consequences

- Local and HTTPS acquisition differ only in how artifact bytes are obtained.
- A ZIP or other aggregate transport container requires a later compatible contract decision; V0 does not
  add one.
- The exact upstream catalog version and fixture-update workflow are recorded with the V0.1 handoff.
- Production release discovery, update polling, rollback policy and quota recovery remain outside P0.7a.
