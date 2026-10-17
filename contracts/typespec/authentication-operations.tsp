using TypeSpec.Http;

namespace Hortinis.Sync {
  @doc("Native host-only Secure, HttpOnly, SameSite=Strict session cookie. Anonymous CSRF sessions have no account or scope authority.")
  model SessionCookieAuth is ApiKeyAuth<
    ApiKeyLocation.cookie,
    "__Host-hortinis-session"
  >;

  @doc("Spring session-backed CSRF proof for every unsafe browser request, including login and action-token completion. Not a login credential.")
  model CsrfHeaderAuth is ApiKeyAuth<ApiKeyLocation.header, "X-CSRF-TOKEN">;

  model AuthenticationSuccessResponse<T> extends NonCacheableResponse {
    @statusCode statusCode: 200;
    @body body: T;
  }

  model PasswordResetAcceptedResponse extends NonCacheableResponse {
    @statusCode statusCode: 202;
    @body body: Models.PasswordResetAccepted;
  }

  @error
  model AuthenticationInvalidRequestResponse extends NonCacheableResponse {
    @statusCode statusCode: 400;
    @body body: Models.AuthenticationInvalidRequestError;
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

  alias PublicAuthenticationFailures =
    | AuthenticationInvalidRequestResponse
    | AccessDeniedResponse
    | AuthenticationThrottledResponse
    | AccessUnavailableResponse;

  @route("/api/v1/auth")
  interface AuthenticationReadOperations {
    @get
    @route("/csrf")
    @useAuth(NoAuth)
    @TypeSpec.OpenAPI.operationId("readAuthenticationCsrf")
    @summary("Obtain same-origin Spring CSRF state")
    @doc("May create an anonymous native session. Fetch again after login, logout, credential mutation or session loss. Never cache in a service worker or browser storage.")
    readCsrf():
      | AuthenticationSuccessResponse<Models.CsrfBootstrap>
      | PublicAuthenticationFailures;

    @get
    @route("/session")
    @useAuth(SessionCookieAuth | NoAuth)
    @TypeSpec.OpenAPI.operationId("readAuthenticationStatus")
    @summary("Observe minimal current session authority")
    @doc("Does not create a session. Anonymous status reveals no email, account, pending invitation or scope. Only a current verified active account/security version receives authenticated status. Existing requests refresh native inactivity; no absolute deadline is advertised.")
    readStatus():
      | AuthenticationSuccessResponse<Models.AuthenticationStatus>
      | PublicAuthenticationFailures;
  }

  @route("/api/v1/auth")
  @useAuth([SessionCookieAuth, CsrfHeaderAuth])
  interface AuthenticationMutationOperations {
    @post
    @route("/login")
    @TypeSpec.OpenAPI.operationId("authenticateAccountPassword")
    @summary("Log in with verified email and password through Spring form login")
    @doc("Native UsernamePasswordAuthenticationFilter with email parameter, DaoAuthenticationProvider, session fixation protection and JSON success/failure handlers. Unknown, pending, disabled and wrong-password cases share 401. No redirect, partial authority, remember-me or token login. Reject repeated/extra fields, query parameters, malformed encoding or bodies over 16384 bytes with 400.")
    authenticateAccountPassword(
      @header contentType: "application/x-www-form-urlencoded",
      @body request: Models.PasswordLoginRequest,
    ):
      | AuthenticationSuccessResponse<Models.FullAuthenticationStatus>
      | AuthenticationFailedResponse
      | PublicAuthenticationFailures;

    @post
    @route("/invitations/accept")
    @TypeSpec.OpenAPI.operationId("acceptAccountInvitation")
    @summary("Activate the exact invited pending account without logging in")
    @doc("Token purpose is invitation, fixed by this route. Validate the new password, then atomically recheck pending status, consume the current unexpired token and activate its preassigned account/scope. Invalid, expired, replaced, consumed, disabled or wrong-purpose tokens share 409. GET never consumes. No account/email/scope selection or authenticated session.")
    acceptInvitation(@body request: Models.InvitationAcceptanceRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationPreconditionFailedResponse
      | PublicAuthenticationFailures;

    @post
    @route("/password-reset/request")
    @TypeSpec.OpenAPI.operationId("requestAccountPasswordReset")
    @summary("Request a password-reset email with a generic acknowledgement")
    @doc("202 does not confirm an account, token issuance or SMTP delivery. Only verified active accounts are eligible. Per-email mail quotas are silently acknowledged for all addresses; request/source/global overload is generic 429. 503 is reserved for account-independent infrastructure failure, never recipient-specific SMTP failure.")
    requestReset(@body request: Models.PasswordResetRequest):
      | PasswordResetAcceptedResponse
      | PublicAuthenticationFailures;

    @post
    @route("/password-reset/complete")
    @TypeSpec.OpenAPI.operationId("completeAccountPasswordReset")
    @summary("Reset the token-bound account password without logging in")
    @doc("Route fixes reset purpose. Password validation precedes the atomic current-token/account recheck, token consumption, password/security-version update, token invalidation and native session revocation. Invalid or ineligible tokens share 409. Rollback preserves prior credentials and usable token. No automatic login.")
    completeReset(@body request: Models.PasswordResetCompletionRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationPreconditionFailedResponse
      | PublicAuthenticationFailures;

    @post
    @route("/password")
    @TypeSpec.OpenAPI.operationId("changeAccountPassword")
    @summary("Change the authenticated account password and revoke all sessions")
    @doc("Require current verified active authority and current password. Validate the new password, then transactionally recheck credentials/security version, update password, invalidate outstanding reset tokens and revoke every native account session. Require normal login afterward.")
    changePassword(@body request: Models.PasswordChangeRequest):
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | AuthenticationFailedResponse
      | AuthenticationRequiredResponse
      | AuthenticationPreconditionFailedResponse
      | PublicAuthenticationFailures;

    @post
    @route("/logout")
    @TypeSpec.OpenAPI.operationId("logoutAccountSession")
    @summary("Log out the cookie's current session through Spring Security")
    @doc("Bodyless CSRF-protected native LogoutFilter with SecurityContextLogoutHandler, CsrfLogoutHandler and fixed JSON LogoutSuccessHandler. Invalidate the current cookie session; no target parameter or custom logout engine. With valid CSRF anonymous logout also completes. Stale/missing CSRF returns 403, requiring fresh bootstrap/status before a deliberate retry; never replay a stored logout against a new login.")
    logout():
      | AuthenticationSuccessResponse<Models.AuthenticationCompleted>
      | PublicAuthenticationFailures;
  }
}
