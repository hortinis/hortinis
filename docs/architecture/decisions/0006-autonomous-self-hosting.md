# ADR-0006: Autonomous self-hosting

- Status: Accepted

## Context

An operator must be able to deploy and operate Hortisys using only the contents and documented dependencies of this repository.

## Decision

Keep deployment definitions, database lifecycle resources, storage configuration, backup guidance, restore guidance, upgrade guidance, and health-check guidance under `infrastructure/` and `docs/deployment/`.

Runtime dependencies must be explicit, replaceable where practical, and documented. A default installation must not require an undisclosed service.

## Consequences

- Setup and recovery paths are treated as product behavior and tested when tooling exists.
- Configuration uses documented inputs and safe defaults.
- Operators retain control of application data and backups.
- Deployment documentation must cover installation, upgrade, rollback, backup, restore, monitoring, and failure diagnosis.

No hosting platform, container runtime, database, or orchestration system is selected by this record.
