package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AccountActionService;
import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.authentication.PasswordPolicy;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import com.hortinis.sync.protocol.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CSRF remains in the native filter chain; action tokens never authenticate a browser. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
public final class AuthenticationActionController {
  private static final String NEW_PASSWORD = "newPassword";

  private final AccountActionService actions;
  private final AuthenticationAttemptLimiter attempts;
  private final CurrentAuthenticationAuthorityPersistence authority;

  /** Delegates workflows while retaining bounded request admission. */
  public AuthenticationActionController(
      AccountActionService actions,
      AuthenticationAttemptLimiter attempts,
      CurrentAuthenticationAuthorityPersistence authority) {
    this.actions = actions;
    this.attempts = attempts;
    this.authority = authority;
  }

  /** Acknowledges all recipient-specific reset outcomes identically. */
  @PostMapping("/password-reset/request")
  public ResponseEntity<?> requestReset(HttpServletRequest request) throws IOException {
    var body = AuthenticationActionBody.read(request, Set.of("email"));
    String canonical;
    try {
      canonical = EmailAddress.canonical(body.get("email"));
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException();
    }
    admit(canonical, request);
    actions.requestReset(canonical);
    return ResponseEntity.accepted()
        .contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .body(Map.of("accepted", true));
  }

  /** Activates only the account bound to the invitation, without login. */
  @PostMapping("/invitations/accept")
  public ResponseEntity<?> accept(HttpServletRequest request) throws IOException {
    return complete(request, ActionTokenPurpose.INVITATION);
  }

  /** Completes a single-use reset without establishing authentication. */
  @PostMapping("/password-reset/complete")
  public ResponseEntity<?> reset(HttpServletRequest request) throws IOException {
    return complete(request, ActionTokenPurpose.RESET);
  }

  /** Requires a current native session and current password proof. */
  @PostMapping("/password")
  public ResponseEntity<?> change(HttpServletRequest request) throws IOException {
    var body = AuthenticationActionBody.read(request, Set.of("currentPassword", NEW_PASSWORD));
    if (!PasswordPolicy.isValidLoginText(body.get("currentPassword"))) {
      throw new InvalidRequestException();
    }
    requirePasswordText(body.get(NEW_PASSWORD));
    var identity =
        AuthenticationIdentity.from(SecurityContextHolder.getContext().getAuthentication());
    var session = request.getSession(false);
    if (session == null) {
      throw new AuthenticationRequiredException();
    }
    admit(identity.account().toString(), request);
    var current =
        authority.find(session.getId(), identity).orElseThrow(AuthenticationRequiredException::new);
    actions.change(current, body.get("currentPassword"), body.get(NEW_PASSWORD));
    return completed();
  }

  private ResponseEntity<?> complete(HttpServletRequest request, ActionTokenPurpose purpose)
      throws IOException {
    var body = AuthenticationActionBody.read(request, Set.of("token", NEW_PASSWORD));
    String secret = body.get("token");
    if (!AuthenticationSecrets.isRandomValue(secret)) {
      throw new InvalidRequestException();
    }
    requirePasswordText(body.get(NEW_PASSWORD));
    admit(AuthenticationSecrets.digest(secret), request);
    actions.complete(purpose, secret, body.get(NEW_PASSWORD));
    return completed();
  }

  private void admit(String key, HttpServletRequest request) {
    if (!attempts.admit(key, request.getRemoteAddr())) {
      throw new AuthenticationOverloadedException();
    }
  }

  private static void requirePasswordText(String password) {
    if (!PasswordPolicy.isValidLoginText(password)
        || password.codePointCount(0, password.length()) < 15) {
      throw new InvalidRequestException();
    }
  }

  private static ResponseEntity<?> completed() {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .body(Map.of("completed", true));
  }
}
