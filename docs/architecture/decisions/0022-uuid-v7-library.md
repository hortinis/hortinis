# ADR-0022: UUIDv7 generation library

- Status: Accepted; narrowed to local identifiers

## Decision

Use the pinned `uuid` package to generate stable local garden and configuration
identifiers through `src/app/utils/uuid-v7.ts`. UUID generation is a
utility, independent of persistence and application workflows.

## Consequences

Persist identifiers when creating local records. Reopening the database preserves
them. Review the package dependency and lockfile integrity metadata together.
