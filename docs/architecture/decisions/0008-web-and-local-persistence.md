# ADR-0008: Web application and local persistence

- Status: Accepted

## Context

The web application must remain usable after installation when no network is available. User writes must commit locally before synchronization is attempted.

## Decision

- Deliver a client-rendered Angular progressive web application without server-side rendering.
- Use Angular standalone components. Keep business and synchronization rules independent of Angular; coordinating services may use Angular dependency injection.
- Use the Angular service worker for versioned application-shell caching.
- Store working data, the synchronization outbox, cursors, tombstones, and required plant reference snapshots in IndexedDB through Dexie 4.
- Version local schemas and provide explicit migrations.
- Request persistent browser storage where supported and provide export and import recovery paths.

## Consequences

- The service worker caches application assets; it does not implement data synchronization.
- Dedicated persistence components inside `apps/web` own IndexedDB operations and explicit transactions. They may be concrete; introduce an interface when orchestration testing or an alternative implementation justifies it. Components do not access Dexie directly.
- Browser storage can still be evicted, so persistence status, backups, and recovery must be visible and testable.
- Offline tests must cover reloads, upgrades, failed synchronization, and interrupted operations.

