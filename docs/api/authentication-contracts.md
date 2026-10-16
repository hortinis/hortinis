# Authentication HTTP contract handoff

- Owner: S9, implementing [ADR-0033](../architecture/decisions/0033-built-in-authentication-and-sessions.md)
  and the [S8 authentication/session specification](../architecture/authentication-and-sessions.md).
- Status: Contract-first definitions and schema/security checks added; runtime adapters pending.
- Source: `contracts/typespec/authentication-models.tsp` and `authentication-operations.tsp`, imported
  by the existing service. Generated OpenAPI 3.1 and Draft 2020-12 schemas remain derived artifacts.

## Cookie, CSRF and current authority

The opaque `__Host-hortinis-session` cookie is a host-only Secure/HttpOnly/Strict session credential.
It is declared as a cookie security scheme and never modeled in JSON. Anonymous/partial/setup/recovery
cookies are not full account authority. Current committed account, session/epoch, expiry and scope
ownership still have to be validated independently before protected work.

Every auth mutation declares both that cookie and `X-CSRF-TOKEN`. CSRF bootstrap is an explicit
same-origin bounded GET and returns only token/header metadata to transient memory. It may establish
a bounded anonymous ceremony; it cannot grant a data session. Native CSRF rejection may also allocate
an anonymous session without ceremony or account authority. Tokens are opaque bounded text generated
by Spring Security, including its UUID format. Session status does not allocate or rotate a
session; requests to an existing session refresh native inactivity without renewing normal factors. Anonymous status omits account identity, setup availability,
factor registration and scope state. Full status exposes the authorized account and a non-secret
logout target distinct from the cookie secret, plus the absolute deadline.

Synchronization reads declare cookie or NoAuth; unsafe synchronization/binding requests declare
cookie plus CSRF or NoAuth. That alternative describes explicit committed access modes only. An
authenticated mode must never choose NoAuth because a cookie/factor is absent or invalid. Public
compatibility discovery still contains no account/scope/setup authority. Generation/reconciliation
contracts retain their independent implementation gates.

## Closed pathways

All auth routes are under `/api/v1/auth`; no account or scope is selected in a URL. The contract declares:

- explicit CSRF/session observation and authorized credential/session listing;
- controlled setup ticket/password followed by verified passkey activation and show-once recovery codes;
- password followed by a same-account user-verified passkey, plus recent-authentication step-up;
- protected password/passkey/recovery-code changes and targeted session revocation;
- forgotten-password proof using passkey plus code, lost-passkey proof using password plus code,
  and separate controlled host-ticket recovery, each limited to factor replacement before normal login;
- explicit qualifying activity and target-fenced protected logout.

Bodies, options and response variants are closed. Framework example URLs/DTOs, arbitrary extension
objects, private keys, password hashes, session IDs and caller-declared scope/account fields cannot be
added implicitly. Credential/session listing is for the authorized account; only a valid two-proof
passkey-replacement ceremony can list that account's credentials while lacking garden authority.
Recent normal factors are still required for ordinary credential/session changes. At most eight
credentials/five full sessions and exactly ten newly generated recovery codes are modeled.

No self-registration, email recovery, provider fallback, ordinary operator garden login or device
inference is introduced. Setup owns only the exact fresh empty sole scope. Existing no-auth claims,
multi-user provisioning and device enrollment remain S10-S12 contracts/workflows.

## Encoding and adapter validation

Passwords have separate login and new-password shapes; the adapter checks exact Unicode scalar/UTF-8
bounds without normalization or truncation. Login identifiers are ASCII and canonicalize separately.
Random 32-byte values have canonical unpadded base64url encoding, including final unused-bit checks.
Credential/client-data/attestation/proof fields also constrain canonical base64url and size. IDs/raw IDs
must additionally agree in the adapter; schemas cannot express that cross-field invariant.

Registration options force resident keys, required user verification and no attestation collection.
Algorithms are limited to ES256, RS256 and EdDSA supported by the selected verifier. Authentication
options omit `allowCredentials`, avoiding public account/credential association. Actual signature,
RP/origin/challenge, presence, verification, same-account binding, counters and one-use validation
remain mandatory established-library behavior; structurally valid synthetic bytes are not valid proof.
Requests use 16-KiB normal and 64-KiB WebAuthn limits with bounded parsing/decoded fields. These narrow
limits do not weaken synchronization parsing or authorize logging an invalid credential envelope.

## Errors, caching and logout

Every declared response/error is no-store. Fixed errors distinguish invalid input, failed/required
authentication, denied access, failed authentication precondition, temporary throttling and unavailable
access without identifying a user or exposing input. All relevant routes declare 429 with fixed
60-second Retry-After. Runtime failed/unknown-account work and timing must follow the same bounded policy;
JSON filter/error adapters must not leak framework HTML, redirects, exception text or credentials.

Logout carries only the original non-authorizing target under cookie/CSRF proof. A matching target
invalidates; no active full target completes idempotently; a different target denies without touching
it. Spring Session owns cookie publication and expiration, including deletion on logout or stale-cookie
requests. A delayed response may clear a newer browser cookie and require reauthentication; server
revocation and pending offline changes remain intact. Durable offline pending logout/all-tab fencing
remains browser work, not authority derived from the target field.

## Current evidence and remaining gates

Contract validation checks matching generated shapes, closed required fields/nested envelopes,
canonical/size limits, Unicode password bounds, forced verification/algorithms, anonymous minimization,
recovery-code count/uniqueness, cookie/CSRF schemes, every mutation's joint security and no-store errors.
The complete explicit API path inventory includes the new routes without hiding unexpected paths.
Private service tests now exercise controlled sole-account activation, actual Argon2id and signed
WebAuthn login/step-up, atomic JDBC/native-cookie rotation, current-counter and capacity races,
rollback and fixed expiry. Reviewed credential/session services invalidate all old authority on
factor changes, preserve the last key and cap, and implement explicit activity and target-bound
logout without cookie writes. Restricted recovery tests cover code consumption, pending-state denial,
fixed deadlines, current-proof publication, normal login after replacement, key-selection rotation,
cap replacement and rollback. Forgotten-password options perform no account/code lookup; the pending
state is account-independent and cannot authorize reset. Lost-key selection rotates the limited
parent and its cookie/CSRF before issuing a bound nonce, preserving the original deadline.
Controlled operator recovery also has real signed-proof/current-ticket tests: exact target/purpose/
installation claim, bounded retry, new compliant password plus user-verified registration, removal
of obsolete credentials/codes/authority, rollback and normal login afterward. The reviewed host command
uses an isolated non-web context, controlled protected files and fixed diagnostics. Physical lifecycle
adapters purge application records at startup and on a schedule; Spring Session owns native idle
expiry and scheduled deletion. Requests reject expired authority without waiting for physical cleanup.
The same claimed setup ceremony can reissue options after interruption without extending its deadline.

Private HTTP tests now use the actual Spring Session JDBC servlet wrappers, established header-only
CSRF filter, exact Origin/Fetch Metadata guard and closed bounded parser. Admitted malformed proofs
spend their nonce before parsing; replay and wrong-purpose proofs deny. All contract routes delegate
to the completed services and explicitly project minimized no-store responses. Recovery availability
supports the thirty-day warning after reload without retaining secrets. This is controlled adapter
evidence. S9 now includes the selected production runtime wiring, browser coordination and
authenticated HTTPS topology. S10-S15 and foundation release gates remain applicable; a generated
contract neither activates authentication nor certifies compliance.

The protected `GET /api/v1/auth/recovery-codes` projects only unused unexpired code availability,
common expiry and the trusted-server-clock warning during the last thirty days. The zero-code variant
has no expiry. This supports account warnings after reload without retaining code or CSRF secrets;
limited recovery states cannot read this full-account metadata and the read never renews inactivity.
