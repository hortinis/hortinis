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

S4 requires `HORTINIS_ACCESS_MODE` (Spring property `hortinis.access.mode`) and
`HORTINIS_ACCESS_PUBLIC_ORIGIN` (`hortinis.access.public-origin`) explicitly, with no default.
The public origin is the browser-facing HTTP(S) origin without a trailing slash, credentials, path,
query, or fragment; malformed or missing values fail startup without echoing configuration.
Only `single-user-no-auth` currently starts; both authenticated modes fail closed until their actual
account/session implementation exists. Startup commits the initial selected mode to installation
metadata and rejects mismatches on restart. A configuration edit cannot claim a scope or downgrade
an authenticated installation. Missing designated scope denies setup and rolls back initial mode
commit. Fresh databases are required for this pre-release schema revision; no upgrade migration exists.

Every reachable caller can read/modify a no-auth installation's data. Browser cross-origin mutation
checks do not authenticate callers or protect against same-origin compromise. The current guard uses
the explicit `hortinis.access.public-origin` and ignores Host/forwarded claims; a production trusted-edge configuration,
TLS, authenticated CSRF/session policy, backup rollback protection, and privacy readiness remain
S8-S15/deployment work. Keep this technical topology restricted to synthetic data.
