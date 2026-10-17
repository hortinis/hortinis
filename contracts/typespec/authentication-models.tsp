@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Models {
  @doc("ASCII dot-atom mailbox and DNS domain, at most 64 local-part bytes and 254 total bytes. Canonical identity is the entire address lowercased with Locale.ROOT; preserve submitted case for delivery. No whitespace, quoted local parts, IP literals, Unicode or provider alias rewriting.")
  @format("email")
  @minLength(3)
  @maxLength(254)
  @pattern("^(?=.{1,64}@)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+(?![\\s\\S])")
  scalar EmailAddress extends string;

  @doc("Exact password text; adapters validate Unicode scalars and the 1024-byte UTF-8 bound without trim, normalization or truncation.")
  @minLength(1)
  @maxLength(128)
  scalar LoginPassword extends string;

  @minLength(15)
  @maxLength(128)
  scalar NewPassword extends string;

  @doc("Canonical unpadded base64url encoding of 32 independent CSPRNG bytes. Invitation and reset secrets are purpose-bound action tokens, never login credentials.")
  @pattern("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048](?![\\s\\S])")
  @minLength(43)
  @maxLength(43)
  scalar ActionToken extends string;

  @doc("Framework CSRF value, including BREACH-masked base64 text; transient memory only.")
  @minLength(1)
  @maxLength(256)
  @pattern("^[A-Za-z0-9_+/=-]+(?![\\s\\S])")
  scalar CsrfValue extends string;

  model CsrfBootstrap {
    token: CsrfValue;
    headerName: "X-CSRF-TOKEN";
  }

  model AnonymousAuthenticationStatus {
    state: "anonymous";
  }

  model FullAuthenticationStatus {
    state: "authenticated";
    accountId: AccountId;
  }

  @TypeSpec.JsonSchema.oneOf
  union AuthenticationStatus {
    anonymous: AnonymousAuthenticationStatus,
    authenticated: FullAuthenticationStatus,
  }

  @doc("Exactly one email and password field, encoded as application/x-www-form-urlencoded. Configure formLogin usernameParameter(email); never implement a JSON login controller.")
  model PasswordLoginRequest {
    email: EmailAddress;
    password: LoginPassword;
  }

  model InvitationAcceptanceRequest {
    token: ActionToken;
    newPassword: NewPassword;
  }

  model PasswordResetRequest {
    email: EmailAddress;
  }

  model PasswordResetCompletionRequest {
    token: ActionToken;
    newPassword: NewPassword;
  }

  model PasswordChangeRequest {
    currentPassword: LoginPassword;
    newPassword: NewPassword;
  }

  model AuthenticationCompleted {
    completed: true;
  }

  @doc("Identical acknowledgement for eligible, unknown, pending, disabled, throttled-by-mail-quota and SMTP-failed reset delivery. Does not confirm account existence or delivery.")
  model PasswordResetAccepted {
    accepted: true;
  }

  model AuthenticationInvalidRequestError {
    code: "INVALID_REQUEST";
    message: "The request is invalid.";
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
