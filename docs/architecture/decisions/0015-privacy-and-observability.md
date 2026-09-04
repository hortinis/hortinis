# ADR-0015: Privacy and observability

- Status: Accepted

## Context

Operators need enough diagnostics to maintain a self-hosted installation, while logs, traces, cookies, and optional providers must not create unnecessary personal-data processing or require optional tracking consent by default.

## Decision

- Emit structured JSON logs with random request, trace, and synchronization operation identifiers.
- Provide health and readiness endpoints without exposing user data.
- Keep OpenTelemetry integration optional and disabled by default.
- Ship without analytics, telemetry, external crash reporting, advertising, optional trackers, or third-party runtime assets.
- Use only cookies strictly necessary to provide explicitly requested application functionality.
- Use allowlisted log fields and redact credentials, tokens, headers, query parameters, and identifiers not needed for the stated diagnostic purpose.
- Never log request or response bodies, garden content, precise locations, photos, email addresses, passwords, or password hashes.
- Separate operational and security logs, restrict access, define retention by purpose, and provide rotation and automatic purge controls.
- Keep external providers disabled until explicitly configured by the operator.
- Document the operator's responsibility as data controller, including information duties and configuration choices.

## Consequences

- The default application does not need a consent banner for optional tracking because it performs no optional tracking.
- Privacy information can still be legally required, and consent is not the only possible legal basis for processing.
- Pseudonymous identifiers can remain personal data and receive the same retention and access protections.
- Debugging that requires user content must use an explicit, time-limited, operator-controlled support procedure rather than ordinary logs.

