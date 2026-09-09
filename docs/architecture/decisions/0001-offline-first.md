# ADR-0001: Offline-first operation

- Status: Accepted

## Context

Garden work can happen with unreliable or absent connectivity. Network availability cannot be a prerequisite for ordinary use.

## Decision

The web application treats local state as the immediate working state. Supported user actions complete locally first and are queued for synchronization when needed. The interface exposes whether changes are local, synchronized, pending, or in conflict.

## Consequences

- Reads and writes for core workflows must work without a network.
- Reloading the installed application must preserve committed local work.
- Pending local work does not expire because a client remains offline or because server synchronization history is compacted.
- Network retries must be safe and must not duplicate accepted changes.
- Initial installation and operations that inherently require a remote source may still need connectivity and must be identified explicitly.
- Storage limits, schema evolution, recovery, and conflict behavior require deliberate design.

The web framework and local database are selected by ADR-0008. The synchronization model is selected by ADR-0010.
