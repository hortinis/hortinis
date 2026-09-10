# ADR-0002: Lightweight dependency boundaries

- Status: Accepted

## Context

Hortinis is expected to remain a modest application maintained by one developer. Its selected frameworks and database are long-term choices. Offline synchronization and business invariants need independent tests, but hypothetical technology replacement does not justify separate build packages for every responsibility.

## Decision

- Start with one Angular application in `apps/web` and one Spring Boot application project in `services/sync`, organized internally by feature and supporting capability.
- Keep business invariants and synchronization decisions in plain TypeScript or Java code without framework, transport, database, filesystem, or vendor dependencies.
- Components and controllers delegate meaningful workflows to services. Services may use Angular or Spring dependency injection and concrete persistence components.
- Keep SQL, Dexie operations, filesystem access, and provider-specific calls in dedicated components. UI components and HTTP controllers do not access persistence directly.
- Expose small feature entry points and avoid dependency cycles. Introduce internal rules, services, and persistence files only when behavior needs them; no feature must contain a fixed set of layers.
- Introduce narrow interfaces for replaceable external providers, synchronization transport, and file/object storage. Persistence interfaces are optional when orchestration tests or an actual alternative implementation justify them; ordinary services do not require interface/implementation pairs.
- Keep transaction boundaries explicit, including atomic local state and outbox writes and atomic server acceptance.
- Validate public wire contracts. Create separate internal or persistence representations when their semantics, lifecycle, or invariants differ; identical shapes do not require mechanical mapping through multiple models.
- Extract a build package or module only when demonstrated reuse or independent dependency enforcement warrants its maintenance cost.

## Consequences

- Pure rules can be tested without Angular, Spring, HTTP, or a database.
- Framework-aware orchestration and concrete persistence are permitted without weakening rule isolation.
- A small set of import and architecture checks protects pure rules, detects cycles, and prevents direct persistence access from components and controllers.
- Integration tests verify real storage and transaction semantics; interfaces and mocks do not establish those guarantees.
- Provider replacement remains isolated through focused adapters. PostgreSQL is a deliberate dependency, not a lowest-common-denominator storage abstraction.
- Feature code stays within each application initially, avoiding mandatory layer packages, duplicate models, and empty scaffolding.
