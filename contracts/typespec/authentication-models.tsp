@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @pattern("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")
  @minLength(3)
  @maxLength(64)
  scalar LoginIdentifier extends string;

  @doc("Exact received password text; runtime validates Unicode scalar and UTF-8 bounds without normalization.")
  @minLength(1)
  @maxLength(128)
  scalar LoginPassword extends string;

  @minLength(15)
  @maxLength(128)
  scalar NewPassword extends string;

  @doc("Canonical unpadded base64url encoding of 32 bytes; semantics depend on the containing field.")
  @pattern("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")
  scalar EncodedRandomValue extends string;

  @doc("Transient session-backed CSRF value; never persist in browser data or carry in URLs.")
  @pattern("^[A-Za-z0-9_-]{1,256}$")
  scalar CsrfValue extends string;

  @pattern("^(?:[A-Za-z0-9_-]{4})*(?:[A-Za-z0-9_-][AQgw]|[A-Za-z0-9_-]{2}[AEIMQUYcgkosw048])?$")
  @minLength(2)
  @maxLength(2048)
  scalar WebAuthnCredentialId extends string;

  @pattern("^(?:[A-Za-z0-9_-]{4})*(?:[A-Za-z0-9_-][AQgw]|[A-Za-z0-9_-]{2}[AEIMQUYcgkosw048])?$")
  @minLength(2)
  @maxLength(4096)
  scalar WebAuthnClientData extends string;

  @pattern("^(?:[A-Za-z0-9_-]{4})*(?:[A-Za-z0-9_-][AQgw]|[A-Za-z0-9_-]{2}[AEIMQUYcgkosw048])?$")
  @minLength(2)
  @maxLength(43691)
  scalar WebAuthnAttestationObject extends string;

  @pattern("^(?:[A-Za-z0-9_-]{4})*(?:[A-Za-z0-9_-][AQgw]|[A-Za-z0-9_-]{2}[AEIMQUYcgkosw048])?$")
  @minLength(2)
  @maxLength(1024)
  scalar WebAuthnProofPart extends string;

  model CsrfBootstrap {
    token: CsrfValue;
    headerName: "X-CSRF-TOKEN";
  }

  model AnonymousAuthenticationStatus {
    state: "anonymous";
  }

  model LimitedAuthenticationStatus {
    state: "password" | "setup" | "recovery";
  }

  model FullAuthenticationStatus {
    state: "authenticated";
    accountId: AccountId;

    @doc("Non-secret logout target, distinct from the session cookie; never authorization.")
    logoutTarget: EncodedRandomValue;

    absoluteExpiresAt: utcDateTime;
  }

  @TypeSpec.JsonSchema.oneOf
  union AuthenticationStatus {
    anonymous: AnonymousAuthenticationStatus,
    limited: LimitedAuthenticationStatus,
    authenticated: FullAuthenticationStatus,
  }

  model PasswordLoginRequest {
    login: LoginIdentifier;
    password: LoginPassword;
  }

  model CurrentPasswordRequest {
    password: LoginPassword;
  }

  model AccountSetupRequest {
    ticket: EncodedRandomValue;
    login: LoginIdentifier;
    password: NewPassword;
  }

  model WebAuthnRelyingParty {
    @minLength(1)
    @maxLength(253)
    id: string;

    name: "Hortinis";
  }

  model WebAuthnUser {
    id: EncodedRandomValue;
    name: LoginIdentifier;
    displayName: "Hortinis account";
  }

  model WebAuthnAlgorithm {
    type: "public-key";
    alg: -7 | -257 | -8;
  }

  model WebAuthnCredentialDescriptor {
    type: "public-key";
    id: WebAuthnCredentialId;
  }

  model WebAuthnRegistrationOptions {
    challenge: EncodedRandomValue;
    rp: WebAuthnRelyingParty;
    user: WebAuthnUser;

    @minItems(1)
    @maxItems(3)
    @TypeSpec.JsonSchema.uniqueItems
    @TypeSpec.OpenAPI.extension("uniqueItems", true)
    pubKeyCredParams: WebAuthnAlgorithm[];

    timeout: 300000;

    @maxItems(8)
    excludeCredentials: WebAuthnCredentialDescriptor[];

    authenticatorSelection: {
      residentKey: "required";
      userVerification: "required";
    };
    attestation: "none";
  }

  model WebAuthnAuthenticationOptions {
    challenge: EncodedRandomValue;

    @minLength(1)
    @maxLength(253)
    rpId: string;

    timeout: 300000;
    userVerification: "required";
  }

  model RegisterPasskeyRequest {
    id: WebAuthnCredentialId;

    @doc("Must equal id in canonical encoding; verified by the adapter before cryptographic work.")
    rawId: WebAuthnCredentialId;

    type: "public-key";
    response: {
      clientDataJSON: WebAuthnClientData;
      attestationObject: WebAuthnAttestationObject;
    };

    @doc("Optional user label; Unicode scalar text excluding NUL.")
    @pattern("^[^\\u0000]*$")
    @maxLength(64)
    label?: string;
  }

  model AuthenticatePasskeyRequest {
    id: WebAuthnCredentialId;
    rawId: WebAuthnCredentialId;
    type: "public-key";
    response: {
      clientDataJSON: WebAuthnClientData;
      authenticatorData: WebAuthnProofPart;
      signature: WebAuthnProofPart;
      userHandle: EncodedRandomValue;
    };
  }

  model RecoveryCodeSet {
    @minItems(10)
    @maxItems(10)
    @TypeSpec.JsonSchema.uniqueItems
    @TypeSpec.OpenAPI.extension("uniqueItems", true)
    codes: EncodedRandomValue[];

    expiresAt: utcDateTime;
  }

  model AvailableRecoveryCodeStatus {
    @minValue(1)
    @maxValue(10)
    remaining: integer;

    expiresAt: utcDateTime;
    expiresSoon: boolean;
  }

  model UnavailableRecoveryCodeStatus {
    remaining: 0;
    expiresSoon: false;
  }

  @TypeSpec.JsonSchema.oneOf
  union RecoveryCodeStatus {
    available: AvailableRecoveryCodeStatus,
    unavailable: UnavailableRecoveryCodeStatus,
  }

  model PasswordChangeRequest {
    currentPassword: LoginPassword;
    newPassword: NewPassword;
  }

  model ForgottenPasswordRequest {
    login: LoginIdentifier;
    recoveryCode: EncodedRandomValue;
  }

  model LostPasskeyRequest {
    login: LoginIdentifier;
    password: LoginPassword;
    recoveryCode: EncodedRandomValue;
  }

  model OperatorRecoveryRequest {
    ticket: EncodedRandomValue;
    newPassword: NewPassword;
  }

  model ResetPasswordRequest {
    newPassword: NewPassword;
  }

  model CredentialSummary {
    id: WebAuthnCredentialId;

    @doc("Optional user label; Unicode scalar text excluding NUL.")
    @pattern("^[^\\u0000]*$")
    @maxLength(64)
    label?: string;
  }

  model CredentialList {
    @maxItems(8)
    credentials: CredentialSummary[];
  }

  model CredentialTargetRequest {
    credentialId: WebAuthnCredentialId;
  }

  model SessionSummary {
    target: EncodedRandomValue;
    createdAt: utcDateTime;
    absoluteExpiresAt: utcDateTime;
    current: boolean;
  }

  model SessionList {
    @maxItems(5)
    sessions: SessionSummary[];
  }

  model LogoutRequest {
    target: EncodedRandomValue;
  }

  model AuthenticationCompleted {
    completed: true;
  }

  model AuthenticationFailedError {
    code: "AUTHENTICATION_FAILED";
    message: "Authentication failed.";
  }

  model AuthenticationPreconditionFailedError {
    code: "AUTHENTICATION_PRECONDITION_FAILED";
    message: "The authentication precondition no longer holds.";
  }

  model AuthenticationThrottledError {
    code: "AUTHENTICATION_THROTTLED";
    message: "Authentication attempts are temporarily limited.";
  }
}
