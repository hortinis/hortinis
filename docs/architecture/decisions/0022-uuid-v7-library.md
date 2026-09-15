# ADR-0022: UUIDv7 generation library

- Status: Accepted

## Context

ADR-0010 requires clients to generate stable UUIDv7 record and operation identifiers. The web
application needs a standards-aligned implementation, while UUID construction is not business logic
and should not be maintained as bespoke code.

## Decision

Use the pinned `uuid` npm package's UUIDv7 generator from a small synchronization-side wrapper. Keep
the package dependency isolated from pure protocol rules and persistence components. Persist generated
identifiers as part of the local operation and never regenerate them during retry or reload.

## Consequences

- UUIDv7 encoding and randomness remain maintained by the dependency rather than application code.
- The dependency version, lockfile entry, and package integrity metadata must be reviewed together.
- Tests must verify canonical lowercase UUIDv7 shape and stable persisted identifiers.
- Replacing the dependency later affects only the wrapper and its focused tests.
