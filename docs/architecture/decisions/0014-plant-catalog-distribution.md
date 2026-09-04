# ADR-0014: Plant catalog distribution contract

- Status: Accepted

## Context

The external plant catalog can become large and changes independently from Hortinis. Clients need a complete verified local snapshot for offline use without duplicating its canonical model in the application domain.

## Decision

- Publish each catalog version as an immutable artifact.
- Describe the artifact with `manifest.json` and a versioned JSON Schema.
- Store catalog entries as deterministic, size-bounded JSON Lines chunks compressed with gzip.
- List every chunk in the manifest with its byte size, entry count, and SHA-256 digest.
- Include at least the schema version, catalog version, and generation timestamp in the manifest.
- Preserve stable, opaque plant identifiers across catalog versions.
- Support acquisition from a local file or generic HTTP source through an adapter.
- Activate a version only after every required chunk passes schema and integrity validation.

## Consequences

- Clients can process entries incrementally and replace only changed chunks.
- Missing or retired catalog entries remain representable in user-owned data.
- Chunk sizing, signing, key distribution, and update cadence remain coordination decisions with the catalog project.
- No Hortinis Cloud endpoint is required.

