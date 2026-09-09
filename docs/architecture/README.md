# Architecture overview

## Goals

Hortinis must:

- remain usable when the network is unavailable;
- synchronize through a separately deployable service when connectivity returns;
- support an autonomous self-hosted installation;
- keep business rules independent of delivery and infrastructure mechanisms;
- replace external providers without rewriting core behavior;
- consume plant reference data through stable identifiers.

All repository content must be written in English, as established by [ADR-0018](decisions/0018-repository-language.md).

## System context

```text
+---------------------+       optional network       +----------------------+
| Web application     | <--------------------------> | Synchronization      |
| local working state |                              | service              |
+----------+----------+                              +----------+-----------+
           |                                                    |
           | local persistence                                  | persistence
           v                                                    v
  +------------------+                               +----------------------+
  | Local data store |                               | Server-side data     |
  +------------------+                               +----------------------+

                 +--------------------------------+
                 | External plant reference data  |
                 | stable identifiers + versions  |
                 +--------------------------------+
```

The diagram is conceptual. It does not prescribe protocols, storage engines, frameworks, or deployment platforms.

## Internal boundaries

Hortinis starts with one Angular application in `apps/web` and one Spring Boot application project in `services/sync`. Each is organized by feature and supporting capability, with no mandatory build package per layer.

Business invariants and synchronization decisions remain plain TypeScript or Java, independent of frameworks and infrastructure. Components and controllers delegate workflows to services. Services may use framework dependency injection and concrete persistence components; dedicated components own SQL, Dexie, filesystem, and provider calls.

Narrow interfaces protect replaceable providers, synchronization transport, and file/object storage. Other interfaces and separate model representations require a concrete behavioral or testing benefit. Transaction boundaries stay explicit. Small dependency checks protect pure rules, prevent cycles, and keep components and controllers from accessing persistence directly.

Separate packages or modules require demonstrated reuse or a need for independent enforcement. Shared HTTP contracts, schemas, and cross-runtime fixtures remain under `contracts`. See ADR-0002 for the rationale and the foundation plan for the physical layout.

## Initial records

- [ADR-0001: Offline-first operation](decisions/0001-offline-first.md)
- [ADR-0002: Lightweight dependency boundaries](decisions/0002-layered-dependencies.md)
- [ADR-0003: Synchronization boundary](decisions/0003-synchronization-boundary.md)
- [ADR-0004: External provider ports](decisions/0004-external-provider-ports.md)
- [ADR-0005: External plant catalog](decisions/0005-external-plant-catalog.md)
- [ADR-0006: Autonomous self-hosting](decisions/0006-autonomous-self-hosting.md)
- [ADR-0007: Languages, runtimes, and frameworks](decisions/0007-languages-runtimes-frameworks.md)
- [ADR-0008: Web application and local persistence](decisions/0008-web-and-local-persistence.md)
- [ADR-0009: Server API and persistence](decisions/0009-server-api-and-persistence.md)
- [ADR-0010: Synchronization protocol model](decisions/0010-synchronization-protocol-model.md)
- [ADR-0011: Workspace and quality toolchain](decisions/0011-workspace-and-quality-toolchain.md)
- [ADR-0012: Containers and continuous integration](decisions/0012-containers-and-ci.md)
- [ADR-0013: Identity and storage defaults](decisions/0013-identity-and-storage-defaults.md)
- [ADR-0014: Plant catalog distribution contract](decisions/0014-plant-catalog-distribution.md)
- [ADR-0015: Privacy and observability](decisions/0015-privacy-and-observability.md)
- [ADR-0016: Project name and license](decisions/0016-project-name-and-license.md)
- [ADR-0017: Privacy-preserving web analytics](decisions/0017-privacy-preserving-web-analytics.md)
- [ADR-0018: Repository language](decisions/0018-repository-language.md)
- [ADR-0019: Web styling foundation](decisions/0019-web-styling-foundation.md)

## Open decisions

See [Open technical decisions](open-decisions.md). Their unresolved status is intentional.
