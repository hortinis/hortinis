# ADR-0002: Layered dependency rule

- Status: Accepted

## Context

Core garden-management rules must remain testable and independent of changing delivery and infrastructure mechanisms.

## Decision

Use four conceptual layers with dependencies directed inward:

```text
Presentation -> Application -> Domain
Infrastructure -> Application and Domain interfaces
```

The domain contains business concepts and invariants. The application layer coordinates use cases and defines the ports it requires. Presentation invokes application use cases. Infrastructure supplies adapters for those ports.

## Consequences

- Domain code cannot depend on presentation, database, transport, filesystem, or provider libraries.
- Application code cannot depend on concrete infrastructure adapters.
- Wiring happens at an outer composition boundary.
- Data-transfer shapes at system boundaries are mapped to internal models.

The physical package layout may evolve, but it must preserve these dependency constraints.
