# ADR-0007: Languages, runtimes, and frameworks

- Status: Accepted

## Context

The browser must execute application behavior offline, while the synchronization server needs reliable transactions and long-term operability. The primary maintainer is experienced with Angular, Java, and Spring.

## Decision

- Build the web application with Angular and strict TypeScript.
- Use Node.js 24 LTS for frontend development and build tooling only.
- Build backend components with Java 25 LTS and Spring Boot 4.
- Use Spring MVC rather than a reactive server stack until a measured requirement justifies one.
- Use supported stable releases and pin exact tool and dependency versions in lockfiles and wrappers.

## Consequences

- Frontend and backend domain implementations cannot share executable source code.
- OpenAPI, JSON Schema, and shared language-neutral conformance fixtures prevent contracts from drifting.
- The server remains authoritative when validating synchronized operations.
- The browser still validates locally so supported work can complete offline.
- Java virtual threads remain an optimization option, not a default performance assumption.

