# ADR-0008: Web application and local persistence

- Status: Accepted

## Context

The web application must remain usable after installation when no network is available. User writes must commit locally before synchronization is attempted.

## Decision

- Deliver a client-rendered Angular progressive web application without server-side rendering.
- Use Angular standalone components and keep Angular outside domain and application code.
- Use the Angular service worker for versioned application-shell caching.
- Store working data, the synchronization outbox, cursors, tombstones, and required plant reference snapshots in IndexedDB through Dexie 4.
- Version local schemas and provide explicit migrations.
- Request persistent browser storage where supported and provide export and import recovery paths.

## Consequences

- The service worker caches application assets; it does not implement data synchronization.
- IndexedDB remains behind an application-owned persistence interface.
- Browser storage can still be evicted, so persistence status, backups, and recovery must be visible and testable.
- Offline tests must cover reloads, upgrades, failed synchronization, and interrupted operations.

