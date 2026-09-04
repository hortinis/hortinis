# ADR-0013: Identity and storage defaults

- Status: Accepted

## Context

A default self-hosted installation must provide identity and file storage without requiring an external identity provider or object-storage service.

## Decision

- Provide built-in local accounts as the autonomous identity baseline.
- Use secure, HTTP-only, same-site session cookies for browser sessions.
- Represent federation as an optional OIDC adapter.
- Defer the authentication library selection until the security implementation is designed.
- Define a narrow `StorageProvider` port in the application layer.
- Use a local filesystem adapter and persistent volume by default.
- Allow an optional S3-compatible adapter without requiring MinIO.

## Consequences

- Authentication and authorization remain available without internet access or a third-party control plane.
- Offline local access, credential recovery, session expiry, and device revocation need an explicit security design before implementation.
- Provider-specific SDKs and errors remain in infrastructure adapters.

