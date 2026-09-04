# ADR-0003: Synchronization boundary

- Status: Accepted

## Context

Offline-first clients need a way to exchange changes across installations after connectivity returns, without coupling the domain to network details.

## Decision

Provide a separately deployable synchronization service under `services/sync`. Shared synchronization concepts belong under `packages/sync`. Application use cases depend on synchronization ports; transport and persistence details remain adapters.

Every synchronized record must have an identity stable across replicas. The eventual protocol must define change ordering, idempotency, retry, conflict detection, conflict resolution, deletion propagation, schema compatibility, and recovery from interrupted exchange.

## Consequences

- The application remains usable when the service is unreachable.
- Clients may retry a change without causing duplicate effects.
- Conflicts are represented explicitly rather than silently discarding accepted local work.
- Protocol compatibility is tested independently of a particular deployment.

The wire protocol, data model, server runtime, and conflict algorithm remain open decisions.
