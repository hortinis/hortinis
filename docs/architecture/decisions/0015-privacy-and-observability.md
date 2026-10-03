# ADR-0015: Privacy and observability

- Status: Accepted
- Date: 2026-09-09

## Context

Operators need enough diagnostics to maintain a self-hosted installation, while logs, traces, cookies, and optional providers must not create unnecessary personal-data processing or require optional tracking consent by default.

## Decision

- Emit structured JSON logs with fresh random request, trace, and event correlation identifiers. Do not
  use a persisted synchronization operation identifier as an ordinary diagnostic correlation field.
- Provide health and readiness endpoints without exposing user data.
- Keep OpenTelemetry integration optional and disabled by default.
- Ship without telemetry, external crash reporting, advertising, optional trackers, third-party analytics, or third-party runtime assets. Local first-party analytics may be enabled by default only within the privacy boundary and activation gates defined by ADR-0017.
- Use only cookies strictly necessary to provide explicitly requested application functionality.
- Use allowlisted log fields and redact credentials, tokens, headers, query parameters, and identifiers not needed for the stated diagnostic purpose.
- Never log request or response bodies, garden content, precise locations, photos, email addresses, passwords, or password hashes.
- Separate operational and security logs, restrict access, define retention by purpose, and provide rotation and automatic purge controls.
- Keep external providers disabled until explicitly configured by the operator.
- Document the operator's responsibility as data controller, including information duties and configuration choices.

## Consequences

- The default application performs no visitor-level, cross-session, third-party, or cross-deployment tracking. Local first-party aggregate analytics may be enabled only under ADR-0017 and require the information, objection, or consent mechanism established by their applicable legal assessment.
- Privacy information can still be legally required, and consent is not the only possible legal basis for processing.
- Pseudonymous identifiers can remain personal data and receive the same retention and access protections.
- Debugging that requires user content must use an explicit, time-limited, operator-controlled support procedure rather than ordinary logs.

## Relationship to later decisions

ADR-0017 supersedes only this record's former prohibition on default analytics and its former consequence that the default application performed no analytics. All other decisions in this record continue to apply.

ADR-0029 refines identifier logging for access and synchronization: raw account, scope, device, record,
operation, and session identifiers do not belong in ordinary logs. Credentials, garden content,
request/response bodies, precise locations, and raw source addresses are not logged. A separate
security log may use only a documented, minimized, purpose-limited pseudonymous subject reference with
restricted access and automatic retention; it is personal data and never an analytics dimension.
The [access data lifecycle](../../privacy/access-data-lifecycle.md) supplies the initial inventory and
proposed periods. S15 must accept effective periods and verify each logging layer before real-data use.
