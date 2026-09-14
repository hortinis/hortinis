# ADR-0021: TypeSpec contract source

- Status: Accepted

## Context

Hortinis needs one language-neutral wire contract consumed by a browser client and an authoritative Java
server. Maintaining OpenAPI and standalone JSON Schema independently risks shape drift. Deriving the
contract from either adapter would make that implementation language the authority and delay contract
review until after implementation.

## Decision

- Author HTTP and language-neutral JSON contracts in TypeSpec.
- Generate OpenAPI 3.1 and JSON Schema Draft 2020-12 from the same TypeSpec declarations where both
  artifacts are needed.
- Commit generated contract artifacts for consumers that do not run TypeSpec. Never edit generated
  artifacts by hand; check that generation reproduces them byte-for-byte.
- Pin a compatible stable TypeSpec compiler and emitter set in the root pnpm workspace.
- Validate generated OpenAPI and standalone JSON Schemas independently from TypeSpec compilation.
- Permit only repository-local contract references during validation. Do not fetch remote references.
- Keep generated TypeScript or Java boundary types optional and defer their generator selection until an
  adapter demonstrates a concrete need.
- Keep TypeSpec contracts limited to wire semantics. Domain rules, persistence models, and internal
  application types remain in their owning runtime.

## Consequences

- TypeSpec becomes a small additional authoring tool, while the generated OpenAPI and schemas remain
  familiar interchange formats.
- Contract changes must review the authored TypeSpec and its generated artifacts together.
- Emitter compatibility for optional and nullable properties, unions, constraints, and closed objects is
  covered by a regression fixture.
- Version-specific emitter behavior must be tested before TypeSpec packages are upgraded.
- Code generation does not imply generated types are domain models or require mechanical mappings when
  their semantics are identical.
