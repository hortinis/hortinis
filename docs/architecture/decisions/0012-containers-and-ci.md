# ADR-0012: Containers and continuous integration

- Status: Accepted

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
