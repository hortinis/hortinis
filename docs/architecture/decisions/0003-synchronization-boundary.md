# ADR-0003: Synchronization boundary

- Status: Accepted

## Context

Offline-first clients need a way to exchange changes across installations after connectivity returns, without coupling the domain to network details.

## Decision

Provide a separately deployable Spring Boot application under `services/sync`. Backend synchronization rules belong in plain Java code within its internal `sync` package, without a separate Gradle module. Browser synchronization rules and coordination belong under `apps/web/src/app/sync`. Language-neutral synchronization schemas and conformance fixtures belong under `contracts/sync`, and HTTP contracts belong under `contracts/openapi`. A narrow browser transport interface isolates network behavior; dedicated persistence components own transactions. Coordination services may use frameworks and concrete persistence, while protocol decisions remain independent of both.

Every synchronized record must have an identity stable across replicas. The eventual protocol must define change ordering, idempotency, retry, conflict detection, conflict resolution, deletion propagation, schema compatibility, and recovery from interrupted exchange or compacted server history.

## Consequences

- The application remains usable when the service is unreachable.
- Clients may retry a change without causing duplicate effects.
- Losing the incremental synchronization path triggers full reconciliation; it does not discard or expire pending local work.
- Conflicts are represented explicitly rather than silently discarding accepted local work.
- Protocol compatibility is tested independently of a particular deployment.

The protocol model and server runtime are selected by ADR-0007, ADR-0009, and ADR-0010. Resource-specific data models and conflict-resolution interfaces remain open until their workflows are defined.
