# ADR-0001: Offline-first operation

- Status: Accepted

## Context

Garden work can happen with unreliable or absent connectivity. Network availability cannot be a prerequisite for ordinary use.

## Decision

The web application treats local state as the immediate working state. Supported user actions complete locally first and are queued for synchronization when needed. The interface exposes whether changes are local, synchronized, pending, or in conflict.

## Consequences

- Reads and writes for core workflows must work without a network.
- Reloading the installed application must preserve committed local work.
- Network retries must be safe and must not duplicate accepted changes.
- Initial installation and operations that inherently require a remote source may still need connectivity and must be identified explicitly.
- Storage limits, schema evolution, recovery, and conflict behavior require deliberate design.

This record does not select a web framework, local database, cache mechanism, or synchronization algorithm.
