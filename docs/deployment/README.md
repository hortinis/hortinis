# Deployment

Hortinis is intended to support an autonomous self-hosted installation.

Docker multi-stage images and Docker Compose are the baseline deployment mechanisms. An optional Dev Container reuses the development Compose topology. No Kubernetes deployment is planned without a demonstrated requirement.

Implementation documentation must cover:

- prerequisites and sizing assumptions;
- installation and configuration;
- secrets handling;
- health checks and observability;
- data backup and verified restore;
- upgrades, compatibility, and rollback;
- storage lifecycle;
- failure diagnosis and recovery.

Deployment resources will live under `infrastructure/docker/`. The default installation uses PostgreSQL and filesystem storage in operator-controlled volumes and does not require a Hortinis-operated service.

The accepted [access design](../architecture/access-and-synchronization-scope.md) requires explicit
server modes, controlled setup, and rejection of authentication downgrades, including restored old
configuration. A no-auth server does not isolate users and requires a documented compensating boundary
before personal-data use. The [privacy lifecycle](../privacy/access-data-lifecycle.md) and
[threat/ASVS records](../security/access-threat-model.md) define the operator inventory, retention,
deletion-aware backup/restore, TLS and service-credential gates. Current development Compose validation
is not production security or real-user deployment authorization.
