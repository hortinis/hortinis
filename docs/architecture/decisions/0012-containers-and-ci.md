# ADR-0012: Containers and continuous integration

- Status: Accepted
- Date: 2026-09-09

## Context

Development and self-hosting must use standard infrastructure without requiring a Hortinis-operated service.

## Decision

- Use Docker Compose for local development and as the baseline self-hosting deployment topology.
- Build production components with multi-stage Dockerfiles.
- Keep all persistent development data in named volumes.
- Provide an optional Dev Container that reuses the Compose environment rather than duplicating it.
- Use GitHub Actions to run formatting checks, linting, type checking, tests, architecture checks, builds, image builds, and Compose validation.
- Do not deploy automatically from the initial CI workflow.
- Do not introduce Kubernetes until an explicit deployment requirement exists.

## Consequences

- Native development remains supported and documented.
- The first dependency download requires network access, but runtime operation does not depend on a package registry or Hortinis service.
- Production and development containers have separate concerns; development tools are not copied into runtime images.

## Development container ownership (H9)

Amended on 2026-10-10.

- The development sync service accepts numeric `HORTINIS_UID` and `HORTINIS_GID` overrides. Both
  default to zero to preserve the existing Gradle image's root execution behavior. Operators may map
  them to the checkout owner's IDs on Linux so generated bind-mounted files remain host-writable.
- Set `GRADLE_USER_HOME` explicitly to the named Gradle-cache mount. A one-shot root service prepares
  ownership of only that volume before sync starts, including when the configured IDs change. It has
  no checkout or PostgreSQL data mount and does not follow cache symlinks during ownership changes.
- Stop sync before changing IDs or repairing existing checkout permissions. Development cache
  initialization never changes checkout ownership or database credentials.
- The isolated test topology retains container-local build output and project caches. Production
  images remain a separate F2 responsibility and will replace the development bind mount.
