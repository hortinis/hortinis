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

## Layering

The intended dependency direction is:

```text
Presentation -> Application -> Domain
Presentation -> Infrastructure adapters -> Application interfaces
```

- **Presentation** handles user interaction and delivery concerns.
- **Application** coordinates use cases, transactions, authorization rules, and ports.
- **Domain** expresses entities, value objects, invariants, and domain services.
- **Infrastructure** implements persistence, transport, storage, and provider adapters.

Only outer layers know implementation details. Inner layers do not import outer layers.

## Initial records

- [ADR-0001: Offline-first operation](decisions/0001-offline-first.md)
- [ADR-0002: Layered dependency rule](decisions/0002-layered-dependencies.md)
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

## Open decisions

See [Open technical decisions](open-decisions.md). Their unresolved status is intentional.
