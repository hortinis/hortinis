# Architecture overview

## Goals

Hortisys must:

- remain usable when the network is unavailable;
- synchronize through a separately deployable service when connectivity returns;
- support an autonomous self-hosted installation;
- keep business rules independent of delivery and infrastructure mechanisms;
- replace external providers without rewriting core behavior;
- consume plant reference data through stable identifiers.

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

## Open decisions

See [Open technical decisions](open-decisions.md). Their unresolved status is intentional.
