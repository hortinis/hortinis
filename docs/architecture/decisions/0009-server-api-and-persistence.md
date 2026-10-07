# ADR-0009: Server API and persistence

- Status: Accepted
- Date: 2026-09-09

## Context

The synchronization service requires a browser-compatible protocol, explicit transaction behavior, and a database suitable for concurrent devices. Persistence concerns must not leak into the domain.

## Decision

- Expose versioned HTTP/JSON endpoints with Spring MVC.
- Define public contracts first in OpenAPI 3.1 and generate boundary types or clients where useful.
- Use PostgreSQL 18 as the server database.
- Implement persistence adapters with Spring JDBC and `JdbcClient` using explicit SQL.
- Manage schema changes with versioned Flyway SQL migrations.
- Do not use JPA or annotate domain classes with persistence metadata in the initial foundation.

## Consequences

- Use one Spring Boot application project under `services/sync`, with internal feature and supporting-capability packages.
- Controllers delegate workflows to services; services may use Spring dependency injection, transaction management, and concrete JDBC persistence components.
- Validate transport models at the HTTP boundary. Map transport or database representations when their semantics, lifecycle, or invariants differ from internal models; do not duplicate identical shapes mechanically.
- Business and synchronization rules remain independent of Spring and JDBC. Dedicated persistence components own SQL and explicit atomic operations.
- Integration tests run against PostgreSQL rather than substituting a database with different semantics.
- Introducing an ORM requires a later decision record and demonstrated need.

## Technical synchronization representations

The technical synchronization domain uses distinct `RecordId` and `OperationId` value types and a
positive `Revision(long)` for accepted state. `Revision.next()` uses exact arithmetic; exhaustion
continues to fail acceptance without partial writes. The existing PostgreSQL `BIGINT` columns and
schema remain unchanged.

Request expectations use `ExpectedRevision(BigInteger)` because the public `PositiveDecimal` scalar
has no upper bound. An expectation beyond the accepted revision range remains a valid request and
follows the existing missing-record, operation-reuse, or revision-conflict path. Narrowing request
expectations to `long` would change this behavior and requires a separate contract decision.

Operation compact constructors own identifier presence, expected-revision presence, and the existing
technical value invariants. The HTTP parser checks JSON shapes and translates construction failures
to `INVALID_REQUEST` locally. Typed records provide structural equality for replay. The JDBC component
reconstructs validated receipt operations and owns all SQL identifier and numeric conversions; the
service computes each accepted revision once before passing it to persistence.

HTTP-specific result, change, record, and tombstone representations explicitly convert identifiers
and revisions to their existing string fields. Jackson annotations belong to these wire types, not
the internal result and change types. The separate representations are justified by their different
invariants and serialization responsibilities under ADR-0002. This refactor adds no dependency,
contract regeneration, migration, or production business functionality. ADR-0031's existing
compatibility gate still applies to previously accepted oversized history.
