# ADR-0020: Catalog release coordination

- Status: Accepted

## Context

ADR-0005 and ADR-0014 left catalog schema ownership, chunk sizing, signing and release cadence for
coordination with the external catalog project. The `hortinis-plants` repository has now validated those
choices in its ADR-0004 through ADR-0006 and defines the catalog's local-validation track.

Hortinis must record the coordinated result without redefining the upstream schema or treating a local
validation artifact as a production release.

## Decision

- The `hortinis-plants` repository is authoritative for the language-neutral catalog schemas. Hortinis
  pins compatible generated copies and conformance fixtures under `contracts/catalog/`; it does not
  redefine the catalog model.
- Compressed JSON Lines chunks target approximately 1 MiB and must not exceed 2 MiB.
- The first production release is unsigned. Authenticity relies on explicit operator selection of a local
  artifact or authenticated HTTPS; manifest SHA-256 values provide integrity relative to that trusted
  source.
- Normal catalog publication is quarterly when accepted changes exist, with additional correction
  releases for urgent data or rights issues. Empty scheduled releases are not published.
- The non-publishable `dev-validation` profile may be used for V0. It follows the same manifest, schema,
  JSONL.gz, hashing and attribution contracts as a production artifact but makes no `fr-mvp` completeness
  claim.
- V0 local acquisition accepts an explicitly selected flat set of artifact files. A catalog source exposes
  those files as path-to-bytes reads to the common compatibility, integrity, validation, staging and
  activation pipeline. A later HTTPS source uses the same boundary.
- Hortinis validates artifact contents with its pinned trusted schema copies. Schemas included in a
  selected artifact are distribution material, not dynamically trusted validation authority.

## Consequences

- Local and HTTPS acquisition differ only in how artifact bytes are obtained.
- Introducing a ZIP or another aggregate transport container requires a later compatible contract
  decision; V0 does not add one.
- The complete generated catalog remains outside the Hortinis repository. Only pinned schemas and small
  valid or invalid conformance fixtures are committed.
- Signing and key distribution remain deferred until the threat model requires them.
- The exact pinned catalog version and fixture-update workflow remain implementation choices in P0.7a.
