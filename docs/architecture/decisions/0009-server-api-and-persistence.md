# ADR-0009: Server API and persistence

- Status: Accepted

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

- Transport models are mapped to application commands and results.
- Database records are mapped to domain and application models by infrastructure adapters.
- Integration tests run against PostgreSQL rather than substituting a database with different semantics.
- Introducing an ORM requires a later decision record and demonstrated need.

