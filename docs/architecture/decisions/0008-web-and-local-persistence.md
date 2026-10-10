# ADR-0008: Web application and local persistence

- Status: Accepted
- Date: 2026-09-09

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


## Browser persistence lifecycle (H7)

`StoragePersistence` feature-detects `navigator.storage.persisted()` and `persist()` independently.
Application startup checks the current origin's persistence without requesting permission. The first
successful local create, replace, or delete commit (including unsent create/delete cancellation) in
an application session requests persistence unless it is already confirmed. Concurrent commits share
one automatic request; denied or failed requests are not repeated on subsequent writes in that session.
A new application session may request again after a successful local commit. Failed local transactions
and invalid input do not request permission. The request runs outside IndexedDB transactions and never
delays the local result or synchronization recovery. Browser exceptions are contained without logging.
Startup checking finishes before requesting so a late check cannot overwrite a granted result.
Destroyed application scopes neither start new requests nor publish late permission results.

The readonly status is `persistent` only after browser confirmation, `best-effort` when supported but
unconfirmed (including checking, denial, or API failure), and `unsupported` when the request API is
missing. An existing grant can still be confirmed when only `persisted()` is available. No permission
marker is stored in IndexedDB; the browser remains authoritative for the origin's storage mode.

The shell displays persistence status and warns when a non-persistent store has a non-empty outbox,
including after reload and changes from another tab. Loading and failed outbox observations remain
separate from a confirmed empty outbox. Observation failure is visible. An empty outbox does not prove
all local intent is synchronized: quarantined operations retain the separate H6 rejection status.

### Browser behavior

- The API requires a secure context (HTTPS or a browser-trusted local development origin). Feature
  detection handles missing APIs; support and a granted request must not be assumed.
- Firefox presents a permission prompt. Chromium browsers such as Chrome and Edge generally decide
  automatically using interaction history. Safari also decides automatically; WebKit added full
  Storage API support in Safari 17 and uses heuristics including Home Screen installation.
- Private browsing may use different storage policies and normally removes data when the session ends.
- A grant protects against automatic storage eviction; it does not prevent users clearing site data,
  guarantee unlimited quota, or replace backups. Export/import recovery remains separate planned work.

Sources: [Storage Standard](https://storage.spec.whatwg.org/),
[Mozilla storage quotas and eviction guidance](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria),
and [WebKit storage policy](https://webkit.org/blog/14403/updates-to-storage-policy/).
Automated browser coverage remains Chromium-only and uses deterministic faked permission outcomes;
Firefox and Safari behavior above is documented from browser guidance, not verified by this suite.
