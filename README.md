# Hortinis

Hortinis is a self-hostable garden-management application designed to remain useful without a network connection.

## Product boundaries

The repository contains everything needed to build and operate the application:

- an offline-first progressive web application;
- a synchronization service;
- domain and application logic;
- persistence and storage abstractions;
- deployment resources for an autonomous self-hosted installation;
- tests and project documentation.

## Architectural rules

Dependencies flow inward:

```text
Presentation -> Application -> Domain
                         ^
                         |
                  Infrastructure
```

The domain is independent of user-interface, persistence, transport, and vendor-specific concerns. External systems are reached through interfaces owned by the inner layers and implemented by replaceable adapters.

The plant reference catalog is maintained outside this repository. Hortinis integrates it through a defined boundary and refers to catalog entries by stable identifiers.

See [Architecture overview](docs/architecture/README.md) for the initial decisions.

## Product specifications

Product documentation separates direction, accepted scope, and unresolved questions:

- [Product direction and exploratory ideas](docs/product/product-direction.md)
- [Functional decisions and MVP scope](docs/product/functional-decisions.md)
- [Open questions and conversation handoff](docs/product/open-questions.md)

All repository content must be written in English. See [ADR-0018: Repository language](docs/architecture/decisions/0018-repository-language.md).

## Planned repository layout

```text
apps/web/                  Angular PWA
backend/domain/            framework-independent Java domain
backend/application/       use cases and ports
backend/sync-protocol/     synchronization primitives
backend/adapters/          replaceable backend adapters
services/sync/             Spring Boot synchronization service
contracts/                 OpenAPI and JSON Schema contracts
infrastructure/docker/     development and self-hosting resources
docs/                      product, architecture, development, deployment, API, and user documentation
tooling/                   shared tooling configuration
tests/                     cross-component and architecture tests
```

## Status

The architecture and foundation technologies are selected and recorded in the architecture decision records. The executable scaffold has not been created yet, and no business feature has been implemented.


## License

Hortinis is licensed under GNU Affero General Public License version 3 only. See [LICENSE](LICENSE).
