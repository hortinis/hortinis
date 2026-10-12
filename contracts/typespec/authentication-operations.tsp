using TypeSpec.Http;

namespace Hortinis.Sync {
  @doc("Host-only Secure, HttpOnly, SameSite=Strict session cookie. A limited cookie grants no garden authority; current account/session state is checked independently.")
  model SessionCookieAuth is ApiKeyAuth<
    ApiKeyLocation.cookie,
    "__Host-hortinis-session"
  >;

  @doc("Session-backed CSRF proof for every unsafe authenticated-mode request, including pre-authentication ceremonies. Not a login credential.")
  model CsrfHeaderAuth is ApiKeyAuth<ApiKeyLocation.header, "X-CSRF-TOKEN">;

  model AuthenticationSuccessResponse<T> extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: T;
  }

  @error
  model AuthenticationFailedResponse extends NonCacheableResponse {
    @statusCode statusCode: 401;
    @body body: Models.AuthenticationFailedError;
  }

  @error
  model AuthenticationPreconditionFailedResponse extends NonCacheableResponse {
    @statusCode statusCode: 409;
    @body body: Models.AuthenticationPreconditionFailedError;
  }

  @error
  model AuthenticationThrottledResponse extends NonCacheableResponse {
    @statusCode statusCode: 429;
    @header("Retry-After") retryAfter: "60";
    @body body: Models.AuthenticationThrottledError;
  }

  alias AuthenticationFailures =
    | InvalidRequestResponse
    | AuthenticationFailedResponse
    | AuthenticationRequiredResponse
    | AccessDeniedResponse
    | AuthenticationPreconditionFailedResponse
    | AuthenticationThrottledResponse
    | AccessUnavailableResponse;

  @route("/api/v1/auth")
  interface AuthenticationReadOperations {
    @get
    @route("/csrf")
    @useAuth(NoAuth)
    @TypeSpec.OpenAPI.operationId("readAuthenticationCsrf")
    @summary("Obtain bounded same-origin CSRF state")
    @doc("Explicit coordinated ceremony bootstrap only. May create a bounded anonymous HttpOnly cookie, never a data-authorized account. Does not extend qualifying inactivity.")
    readCsrf():
      | AuthenticationSuccessResponse<Models.CsrfBootstrap>
      | AuthenticationFailures;

    @get
    @route("/session")
    @useAuth(SessionCookieAuth | NoAuth)
    @TypeSpec.OpenAPI.operationId("readAuthenticationStatus")
    @summary("Observe current session status")
    @doc("Return only current cookie authority; anonymous responses omit account, factor registration, setup availability and scope information. Does not allocate a session or extend qualifying inactivity.")
    readStatus():
      | AuthenticationSuccessResponse<Models.AuthenticationStatus>
      | AuthenticationFailures;

    @get
    @route("/sessions")
    @useAuth(SessionCookieAuth)
    @TypeSpec.OpenAPI.operationId("readAccountSessions")
    @summary("List the current account's active sessions")
    @doc("Require full account authority; expose only non-authorizing targets and necessary lifecycle times, never cookies, source addresses or another account's sessions.")
    readSessions():
      | AuthenticationSuccessResponse<Models.SessionList>
      | AuthenticationFailures;

    @get
    @route("/recovery-codes")
    @useAuth(SessionCookieAuth)
    @TypeSpec.OpenAPI.operationId("readAccountRecoveryCodeStatus")
    @summary("Observe current recovery-code availability")
    @doc("Require current full account authority. Return only the number of unexpired unused codes, their common expiry and the server-clock thirty-day warning. Never return codes or verifiers; does not renew inactivity.")
    readRecoveryCodeStatus():
      | AuthenticationSuccessResponse<Models.RecoveryCodeStatus>
      | AuthenticationFailures;

    @get
    @route("/credentials")
    @useAuth(SessionCookieAuth)
    @TypeSpec.OpenAPI.operationId("readAccountCredentials")
    @summary("List the current account's passkeys")
    @doc("Require full account authority or a valid two-proof passkey-replacement ceremony; never return credential private keys, public-key internals or another account's metadata.")
    readCredentials():
      | AuthenticationSuccessResponse<Models.CredentialList>
      | AuthenticationFailures;
  }

  @route("/api/v1/auth")
  @useAuth([SessionCookieAuth, CsrfHeaderAuth])
  interface AuthenticationMutationOperations {
    @post
    @route("/setup")
    @TypeSpec.OpenAPI.operationId("beginAccountSetup")
    @summary("Begin controlled sole-account setup")
    @doc("Require the exact unexpired operator ticket and fresh empty installation; password policy and passkey proof precede activation. Never claim an existing no-auth scope.")
    beginAccountSetup(@body request: Models.AccountSetupRequest):
      | AuthenticationSuccessResponse<Models.WebAuthnRegistrationOptions>
      | AuthenticationFailures;

    @post
    @route("/setup/passkey")
    @TypeSpec.OpenAPI.operationId("completeAccountSetup")
    @summary("Complete controlled account activation")
    @doc("Consume setup proof and atomically activate the intended account/sole-scope owner with a verified passkey. Return recovery codes once; this is not a full garden session.")
    completeAccountSetup(@body request: Models.RegisterPasskeyRequest):
      | AuthenticationSuccessResponse<Models.RecoveryCodeSet>
      | AuthenticationFailures;

    @post
    @route("/login/password")
    @TypeSpec.OpenAPI.operationId("authenticateAccountPassword")
    @summary("Verify the password factor")
    @doc("Return discoverable-credential options only after valid password proof. Partial authority cannot access binding or content; failed login shapes do not identify an account.")
    authenticateAccountPassword(@body request: Models.PasswordLoginRequest):
      | AuthenticationSuccessResponse<Models.WebAuthnAuthenticationOptions>
      | AuthenticationFailures;

    @post
    @route("/login/passkey")
    @TypeSpec.OpenAPI.operationId("authenticateAccountPasskey")
    @summary("Complete account login")
    @doc("Verify the same-account user-present and user-verified assertion against the consumed challenge and rotate the opaque cookie/CSRF state before full authorization.")
    authenticateAccountPasskey(
      @body request: Models.AuthenticatePasskeyRequest,
    ):
      | AuthenticationSuccessResponse<Models.FullAuthenticationStatus>
      | AuthenticationFailures;

    @post
    @route("/step-up/password")
    @TypeSpec.OpenAPI.operationId("beginRecentAuthentication")
    @summary("Begin recent two-factor authentication")
    @doc("Require the current full account and current password. Preserve the original absolute session deadline while rotating into the bounded same-account ceremony.")
    beginRecentAuthentication(@body request: Models.CurrentPasswordRequest):
      | AuthenticationSuccessResponse<Models.WebAuthnAuthenticationOptions>
      | AuthenticationFailures;

    @post
    @route("/step-up/passkey")
    @TypeSpec.OpenAPI.operationId("completeRecentAuthentication")
    @summary("Complete recent two-factor authentication")
    @doc("Require a valid same-account user-verified challenge, rotate cookie/CSRF state and preserve the original absolute deadline.")
    completeRecentAuthentication(
      @body request: Models.AuthenticatePasskeyRequest,
    ):
      | AuthenticationSuccessResponse<Models.FullAuthenticationStatus>
      | AuthenticationFailures;

    @post
    @route("/password")
    @TypeSpec.OpenAPI.operationId("changeAccountPassword")
    @summary("Change the current account password")
    @doc("Require current password and recent normal factors. Update the verifier and authority epoch, invalidate old sessions and return to normal login.")
    changeAccountPassword(@body request: Models.PasswordChangeRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/credentials/options")
    @TypeSpec.OpenAPI.operationId("beginPasskeyRegistration")
    @summary("Begin additional passkey registration")
    @doc("Require recent full authentication and the eight-credential bound. Does not enroll a device or change account ownership.")
    beginPasskeyRegistration():
      | AuthenticationSuccessResponse<Models.WebAuthnRegistrationOptions>
      | AuthenticationFailures;

    @post
    @route("/credentials")
    @TypeSpec.OpenAPI.operationId("registerAccountPasskey")
    @summary("Register an additional account passkey")
    @doc("Verify and consume the current registration challenge under recent full account authority; no device or scope inference.")
    registerAccountPasskey(@body request: Models.RegisterPasskeyRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/credentials/revoke")
    @TypeSpec.OpenAPI.operationId("revokeAccountPasskey")
    @summary("Revoke one account passkey")
    @doc("Require recent full authentication and same-account ownership; deny removal of the last usable passkey. Invalidate old authority atomically.")
    revokeAccountPasskey(@body request: Models.CredentialTargetRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/recovery-codes")
    @TypeSpec.OpenAPI.operationId("replaceAccountRecoveryCodes")
    @summary("Replace recovery codes")
    @doc("Require recent normal factors and atomically invalidate the old set; return exactly ten new single-use codes once.")
    replaceAccountRecoveryCodes():
      | AuthenticationSuccessResponse<Models.RecoveryCodeSet>
      | AuthenticationFailures;

    @post
    @route("/recovery/password/options")
    @TypeSpec.OpenAPI.operationId("beginForgottenPasswordRecovery")
    @summary("Begin forgotten-password proof")
    @doc("Start a bounded ceremony without revealing whether login/code identifies an account. A user-verified registered passkey plus an unused code is required before reset authority.")
    beginForgottenPasswordRecovery(
      @body request: Models.ForgottenPasswordRequest,
    ):
      | AuthenticationSuccessResponse<Models.WebAuthnAuthenticationOptions>
      | AuthenticationFailures;

    @post
    @route("/recovery/password/proof")
    @TypeSpec.OpenAPI.operationId("authorizeForgottenPasswordReset")
    @summary("Authorize a restricted password reset")
    @doc("Verify same-account passkey and atomically consume the unused recovery code. This short ceremony cannot access garden data or become a full session.")
    authorizeForgottenPasswordReset(
      @body request: Models.AuthenticatePasskeyRequest,
    ):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/recovery/password")
    @TypeSpec.OpenAPI.operationId("resetForgottenAccountPassword")
    @summary("Complete restricted password reset")
    @doc("Require the unexpired two-proof reset ceremony and accepted new password. Invalidate old sessions/ceremonies and require a fresh normal login.")
    resetForgottenAccountPassword(@body request: Models.ResetPasswordRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/recovery/passkey")
    @TypeSpec.OpenAPI.operationId("beginLostPasskeyRecovery")
    @summary("Authorize restricted passkey replacement")
    @doc("Require current password and unused recovery code for the same account; consume once. Permit only credential selection/replacement, never garden access.")
    beginLostPasskeyRecovery(@body request: Models.LostPasskeyRequest):
      | AuthenticationSuccessResponse<Models.CredentialList>
      | AuthenticationFailures;

    @post
    @route("/recovery/passkey/options")
    @TypeSpec.OpenAPI.operationId("beginReplacementPasskeyRegistration")
    @summary("Begin replacement of the selected lost passkey")
    @doc("Require the two-proof limited recovery ceremony and the selected same-account credential. A verified replacement precedes revoking the lost credential.")
    beginReplacementPasskeyRegistration(
      @body request: Models.CredentialTargetRequest,
    ):
      | AuthenticationSuccessResponse<Models.WebAuthnRegistrationOptions>
      | AuthenticationFailures;

    @post
    @route("/recovery/passkey/proof")
    @TypeSpec.OpenAPI.operationId("completeLostPasskeyRecovery")
    @summary("Complete restricted passkey replacement")
    @doc("Verify the replacement challenge and atomically revoke the selected lost credential and old sessions. Require fresh normal login.")
    completeLostPasskeyRecovery(@body request: Models.RegisterPasskeyRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/recovery/operator")
    @TypeSpec.OpenAPI.operationId("beginOperatorAccountRecovery")
    @summary("Begin controlled operator recovery")
    @doc("Require a host-issued target-bound one-use ticket after proportionate ownership proof; no ordinary operator garden authority. New password/passkey proof are mandatory.")
    beginOperatorAccountRecovery(
      @body request: Models.OperatorRecoveryRequest,
    ):
      | AuthenticationSuccessResponse<Models.WebAuthnRegistrationOptions>
      | AuthenticationFailures;

    @post
    @route("/recovery/operator/proof")
    @TypeSpec.OpenAPI.operationId("completeOperatorAccountRecovery")
    @summary("Complete controlled factor recovery")
    @doc("Verify replacement proof, consume the exact ticket, replace factors and invalidate old authority. Never grant an ordinary session from the ticket.")
    completeOperatorAccountRecovery(
      @body request: Models.RegisterPasskeyRequest,
    ):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/activity")
    @TypeSpec.OpenAPI.operationId("recordAuthenticatedInteraction")
    @summary("Record qualifying account activity")
    @doc("Require a full session and explicit visible user interaction. Background synchronization/polling cannot extend inactivity or absolute expiry.")
    recordAuthenticatedInteraction():
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/sessions/revoke")
    @TypeSpec.OpenAPI.operationId("revokeAccountSession")
    @summary("Revoke one current-account session")
    @doc("Require recent full account factors and same-account target ownership. Invalidate the target before later data acceptance; never mutate the response cookie.")
    revokeSession(@body request: Models.LogoutRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;

    @post
    @route("/logout")
    @TypeSpec.OpenAPI.operationId("logoutAccountSession")
    @summary("Invalidate the original session target")
    @doc("Require CSRF and compare the non-secret target to current cookie authority. Unknown/inactive targets complete idempotently; a different active target denies without invalidating it. Never emit a session Set-Cookie, including deletion.")
    logoutAccountSession(@body request: Models.LogoutRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailures;
  }
}
