# Hortisys

Hortisys is a self-hostable garden-management application designed to remain useful without a network connection.

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

The plant reference catalog is maintained outside this repository. Hortisys integrates it through a defined boundary and refers to catalog entries by stable identifiers.

See [Architecture overview](docs/architecture/README.md) for the initial decisions.

## Repository layout

To be determined.

## Status

This is an initial scaffold. No programming language, framework, database engine, transport protocol, deployment platform, or build system has been selected yet.


## License

The project license has not been selected yet. See [LICENSE](LICENSE).
