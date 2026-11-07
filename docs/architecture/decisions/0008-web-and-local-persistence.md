# ADR-0008: Web application and local persistence

- Status: Accepted; narrowed to the standalone web application

## Decision

- Deliver a client-rendered Angular PWA using standalone components and strict TypeScript.
- Use the Angular service worker for application-shell caching.
- Store local working data in IndexedDB through Dexie.
- Version schemas and use migrations that preserve gardens and setup state.
- Keep business rules independent of Angular and persistence. Coordinating services
  may use Angular dependency injection; dedicated persistence components own
  IndexedDB access and transactions.

## Consequences

The application can reload its cached shell offline. Browser storage can still be
evicted. Backend synchronization is outside the current application scope.
