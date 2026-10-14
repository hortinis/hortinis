package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationAccountService;
import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationLoginService;
import com.hortinis.sync.authentication.AuthenticationObservationService;
import com.hortinis.sync.authentication.AuthenticationOperatorRecoveryService;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationSessionService;
import com.hortinis.sync.authentication.AuthenticationSetupService;
import com.hortinis.sync.protocol.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact authentication POST workflows, after Origin/header-CSRF filters and before body/crypto. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
public final class AuthenticationMutationController {
  private static final String LOGIN = "login";
  private static final String PASSWORD = "password";
  private static final String NEW_PASSWORD = "newPassword";
  private static final String CODE = "recoveryCode";
  private static final String TICKET = "ticket";
  private static final String TARGET = "target";
  private static final String CREDENTIAL = "credentialId";
  private static final Map<String, Boolean> COMPLETED = Map.of("completed", true);
  private final AuthenticationSetupService setup;
  private final AuthenticationLoginService login;
  private final AuthenticationAccountService credentials;
  private final AuthenticationOperatorRecoveryService operator;
  private final AuthenticationSessionService sessions;
  private final AuthenticationObservationService observation;
  private final AuthenticationProofBoundary proofs;
  private final AuthenticationAttemptLimiter attempts;

  /**
   * Feature services retain policy and transactions; this controller handles only closed wire data.
   */
  public AuthenticationMutationController(
      AuthenticationSetupService setup,
      AuthenticationLoginService login,
      AuthenticationAccountService credentials,
      AuthenticationOperatorRecoveryService operator,
      AuthenticationSessionService sessions,
      AuthenticationObservationService observation,
      AuthenticationProofBoundary proofs,
      AuthenticationAttemptLimiter attempts) {
    this.setup = setup;
    this.login = login;
    this.credentials = credentials;
    this.operator = operator;
    this.sessions = sessions;
    this.observation = observation;
    this.proofs = proofs;
    this.attempts = attempts;
  }

  /** No MVC body argument: the nonce is committed before any proof JSON is read or parsed. */
  @PostMapping({
    "/setup",
    "/setup/passkey",
    "/login/password",
    "/login/passkey",
    "/step-up/password",
    "/step-up/passkey",
    "/password",
    "/credentials/options",
    "/credentials",
    "/credentials/revoke",
    "/recovery-codes",
    "/recovery/password/options",
    "/recovery/password/proof",
    "/recovery/password",
    "/recovery/passkey",
    "/recovery/passkey/options",
    "/recovery/passkey/proof",
    "/recovery/operator",
    "/recovery/operator/proof",
    "/activity",
    "/sessions/revoke",
    "/logout"
  })
  public ResponseEntity<?> mutate(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var transport = new RequestAuthenticationSession(request, response);
    Object result;
    if (AuthenticationProofBoundary.isProof(request)) {
      var proof = proofs.read(request, transport);
      result = completeProof(request.getRequestURI(), transport, proof);
    } else {
      result = begin(request, transport);
    }
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
  }

  private Object completeProof(
      String route,
      RequestAuthenticationSession transport,
      AuthenticationProofBoundary.ProofRequest proof) {
    return switch (route) {
      case "/api/v1/auth/setup/passkey" ->
          setup.complete(transport, proof.receipt(), proof.registration());
      case "/api/v1/auth/login/passkey", "/api/v1/auth/step-up/passkey" -> {
        login.passkey(transport, proof.receipt(), proof.assertion(), route.contains("step-up"));
        yield observation.status(transport);
      }
      case "/api/v1/auth/credentials" -> {
        credentials.register(transport, proof.receipt(), proof.registration());
        yield COMPLETED;
      }
      case "/api/v1/auth/recovery/password/proof" -> {
        credentials.passwordProof(transport, proof.receipt(), proof.assertion());
        yield COMPLETED;
      }
      case "/api/v1/auth/recovery/passkey/proof" -> {
        credentials.replacePasskey(transport, proof.receipt(), proof.registration());
        yield COMPLETED;
      }
      case "/api/v1/auth/recovery/operator/proof" -> {
        operator.complete(transport, proof.receipt(), proof.registration());
        yield COMPLETED;
      }
      default -> throw new InvalidRequestException();
    };
  }

  private Object begin(HttpServletRequest request, RequestAuthenticationSession transport)
      throws IOException {
    String route = request.getRequestURI();
    if (Set.of(
            "/api/v1/auth/credentials/options",
            "/api/v1/auth/recovery-codes",
            "/api/v1/auth/activity")
        .contains(route)) {
      if (request.getInputStream().read() != -1) {
        throw new InvalidRequestException();
      }
      return bodyless(route, request, transport);
    }
    var body = AuthenticationRequestBody.read(request.getInputStream(), false);
    return switch (route) {
      case "/api/v1/auth/setup" -> {
        body.fields(Set.of(TICKET, LOGIN, PASSWORD), Set.of());
        admit(request, body.login());
        yield AuthenticationResponseBody.registration(
            setup.begin(
                transport, body.random(TICKET), body.login(), body.password(PASSWORD, true)));
      }
      case "/api/v1/auth/login/password" -> {
        body.fields(Set.of(LOGIN, PASSWORD), Set.of());
        admit(request, body.login());
        yield AuthenticationResponseBody.assertion(
            login.password(transport, body.login(), body.password(PASSWORD, false)));
      }
      case "/api/v1/auth/step-up/password" -> {
        body.fields(Set.of(PASSWORD), Set.of());
        admit(request, observation.login(transport));
        yield AuthenticationResponseBody.assertion(
            login.stepUpPassword(transport, body.password(PASSWORD, false)));
      }
      case "/api/v1/auth/password" -> {
        body.fields(Set.of("currentPassword", NEW_PASSWORD), Set.of());
        admit(request, observation.login(transport));
        credentials.password(
            transport, body.password("currentPassword", false), body.password(NEW_PASSWORD, true));
        yield COMPLETED;
      }
      case "/api/v1/auth/credentials/revoke" -> {
        body.fields(Set.of(CREDENTIAL), Set.of());
        credentials.revoke(transport, body.credentialTarget());
        yield COMPLETED;
      }
      case "/api/v1/auth/recovery/password/options" -> {
        body.fields(Set.of(LOGIN, CODE), Set.of());
        admit(request, body.login());
        yield AuthenticationResponseBody.assertion(
            credentials.forgottenPassword(transport, body.login(), body.random(CODE)));
      }
      case "/api/v1/auth/recovery/password" -> {
        body.fields(Set.of(NEW_PASSWORD), Set.of());
        admit(request, observation.login(transport));
        credentials.resetPassword(transport, body.password(NEW_PASSWORD, true));
        yield COMPLETED;
      }
      case "/api/v1/auth/recovery/passkey" -> {
        body.fields(Set.of(LOGIN, PASSWORD, CODE), Set.of());
        admit(request, body.login());
        yield AuthenticationResponseBody.credentials(
            credentials.lostPasskey(
                transport, body.login(), body.password(PASSWORD, false), body.random(CODE)));
      }
      case "/api/v1/auth/recovery/passkey/options" -> {
        body.fields(Set.of(CREDENTIAL), Set.of());
        admit(request, observation.login(transport));
        yield AuthenticationResponseBody.registration(
            credentials.replacementOptions(transport, body.credentialTarget()));
      }
      case "/api/v1/auth/recovery/operator" -> {
        body.fields(Set.of(TICKET, NEW_PASSWORD), Set.of());
        if (!attempts.admitSource(request.getRemoteAddr())) {
          throw new AuthenticationOverloadedException();
        }
        yield AuthenticationResponseBody.registration(
            operator.begin(transport, body.random(TICKET), body.password(NEW_PASSWORD, true)));
      }
      case "/api/v1/auth/sessions/revoke" -> {
        body.fields(Set.of(TARGET), Set.of());
        sessions.revoke(transport, body.random(TARGET));
        yield COMPLETED;
      }
      case "/api/v1/auth/logout" -> {
        body.fields(Set.of(TARGET), Set.of());
        sessions.logout(transport, body.random(TARGET));
        yield COMPLETED;
      }
      default -> throw new InvalidRequestException();
    };
  }

  private Object bodyless(
      String route, HttpServletRequest request, RequestAuthenticationSession transport) {
    return switch (route) {
      case "/api/v1/auth/credentials/options" -> {
        admit(request, observation.login(transport));
        yield AuthenticationResponseBody.registration(credentials.options(transport));
      }
      case "/api/v1/auth/recovery-codes" -> credentials.recoveryCodes(transport);
      case "/api/v1/auth/activity" -> {
        sessions.activity(transport);
        yield COMPLETED;
      }
      default -> throw new InvalidRequestException();
    };
  }

  private void admit(HttpServletRequest request, String canonicalLogin) {
    if (!attempts.admit(canonicalLogin, request.getRemoteAddr())) {
      throw new AuthenticationOverloadedException();
    }
  }
}
