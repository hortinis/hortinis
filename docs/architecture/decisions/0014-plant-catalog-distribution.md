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
- Support acquisition through an adapter from an operator-selected local artifact or an HTTPS source with certificate and hostname verification. Do not permit transport downgrade to plain HTTP.
- Treat a local artifact as trusted only when it was selected by the operator through an explicit import or configuration action.
- Activate a version only after its source is trusted and every required chunk passes schema and integrity validation.

## Consequences

- Clients can process entries incrementally and replace only changed chunks.
- Missing or retired catalog entries remain representable in user-owned data.
- Chunk sizing, signing, key distribution, and update cadence remain coordination decisions with the catalog project.
- Until artifact signing is defined, authenticity relies on the operator's local-artifact selection or the authenticated HTTPS connection; manifest hashes provide integrity only relative to that trusted source.
- No Hortinis Cloud endpoint is required.
