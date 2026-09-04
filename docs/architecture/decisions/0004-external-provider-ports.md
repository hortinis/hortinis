# ADR-0004: External provider ports

- Status: Accepted

## Context

The application may need capabilities supplied by external systems. Core behavior must not become dependent on one vendor or deployment environment.

## Decision

Represent each required external capability as a narrow internal interface. Concrete adapters translate between that interface and a provider API. Configuration and composition select adapters outside the domain.

## Consequences

- Provider-specific models and errors do not leak into the domain.
- A local or test adapter can implement the same interface.
- Switching a provider is isolated to an adapter and configuration, subject to capability differences.
- Timeouts, retries, quotas, and unavailable-provider behavior are defined at the boundary.

No provider is selected by this record.
