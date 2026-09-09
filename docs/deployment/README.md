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
