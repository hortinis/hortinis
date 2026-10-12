package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.AuthenticationChallengePurpose;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationPublication;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Outcome;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Reason;
import com.hortinis.sync.protocol.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/** Commits nonce consumption after transport guards and admission, before reading proof JSON. */
public final class AuthenticationProofBoundary {
  private static final Map<String, AuthenticationChallengePurpose> PURPOSES =
      Map.of(
          "/api/v1/auth/setup/passkey", AuthenticationChallengePurpose.SETUP,
          "/api/v1/auth/login/passkey", AuthenticationChallengePurpose.LOGIN,
          "/api/v1/auth/step-up/passkey", AuthenticationChallengePurpose.STEP_UP,
          "/api/v1/auth/credentials", AuthenticationChallengePurpose.CREDENTIAL,
          "/api/v1/auth/recovery/password/proof", AuthenticationChallengePurpose.RECOVER_PASSWORD,
          "/api/v1/auth/recovery/passkey/proof", AuthenticationChallengePurpose.RECOVER_PASSKEY,
          "/api/v1/auth/recovery/operator/proof", AuthenticationChallengePurpose.OPERATOR);
  private final AuthenticationChallengePersistence challenges;
  private final AuthenticationAttemptLimiter attempts;
  private final AuthenticationPublication publication;
  private final AuthenticationSecurityEventPersistence events;

  /** Trusted receipts and fixed audit codes remain independent of wire envelopes. */
  public AuthenticationProofBoundary(
      AuthenticationChallengePersistence challenges,
      AuthenticationAttemptLimiter attempts,
      AuthenticationPublication publication,
      AuthenticationSecurityEventPersistence events) {
    this.challenges = challenges;
    this.attempts = attempts;
    this.publication = publication;
    this.events = events;
  }

  /** The exact registered POST route determines purpose; no request field can select it. */
  public static boolean isProof(HttpServletRequest request) {
    return "POST".equals(request.getMethod()) && PURPOSES.containsKey(request.getRequestURI());
  }

  /** Called from the MVC entry point after established Origin and header-only CSRF filters. */
  public ProofRequest read(HttpServletRequest request, AuthenticationSessionTransport transport) {
    if (!isProof(request)) {
      throw new IllegalArgumentException("The request is not a registered proof operation.");
    }
    var purpose = PURPOSES.get(request.getRequestURI());
    Event event = event(purpose);
    var receipt =
        publication.attempt(
            event,
            () -> {
              if (!attempts.admitSource(request.getRemoteAddr())) {
                throw new AuthenticationOverloadedException();
              }
              var observation = transport.observe().orElseThrow(AuthenticationFailedException::new);
              return challenges.consume(observation.reference().value(), purpose);
            });
    try {
      var body = AuthenticationRequestBody.read(request.getInputStream());
      boolean registration =
          purpose == AuthenticationChallengePurpose.SETUP
              || purpose == AuthenticationChallengePurpose.CREDENTIAL
              || purpose == AuthenticationChallengePurpose.RECOVER_PASSKEY
              || purpose == AuthenticationChallengePurpose.OPERATOR;
      return registration
          ? new ProofRequest(receipt, body.registration(), null)
          : new ProofRequest(receipt, null, body.assertion());
    } catch (InvalidRequestException | IOException exception) {
      publication.mutate(
          () -> {
            events.append(event, Outcome.DENIED, Reason.INVALID_PROOF);
            return true;
          });
      throw new InvalidRequestException();
    }
  }

  private static Event event(AuthenticationChallengePurpose purpose) {
    return switch (purpose) {
      case SETUP -> Event.SETUP;
      case LOGIN -> Event.LOGIN;
      case STEP_UP -> Event.STEP_UP;
      case CREDENTIAL -> Event.CREDENTIAL_CHANGE;
      case RECOVER_PASSWORD, RECOVER_PASSKEY, OPERATOR -> Event.RECOVERY;
    };
  }

  /** Internal body/receipt pair with fixed diagnostic representation; never a wire response. */
  public record ProofRequest(
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey registration,
      PublicKeyCredential<AuthenticatorAssertionResponse> assertion) {
    @Override
    public String toString() {
      return "AuthenticationProofRequest[redacted]";
    }
  }
}
