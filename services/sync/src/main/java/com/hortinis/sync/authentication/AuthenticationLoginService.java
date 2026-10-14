package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

/** Password plus same-account verified passkey login and recent-authentication workflows. */
public final class AuthenticationLoginService {
  private final AuthenticationPublication publication;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationChallengePersistence challenges;
  private final PasswordVerifier passwords;
  private final AuthenticationPasskeys passkeys;

  /** Crypto is separate from explicit current-account/session publication transactions. */
  public AuthenticationLoginService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    this.publication = publication;
    this.accounts = accounts;
    this.sessions = sessions;
    this.challenges = challenges;
    this.passwords = passwords;
    this.passkeys = passkeys;
  }

  /** Unknown users execute the same selected Argon2id cost before generic rejection. */
  public PublicKeyCredentialRequestOptions password(
      AuthenticationSessionTransport transport, String requestedLogin, String password) {
    return publication.attempt(
        Event.LOGIN,
        () -> {
          String login = PasswordPolicy.canonicalLogin(requestedLogin);
          var observed = accounts.findLogin(login);
          boolean verified =
              passwords.matches(
                  password,
                  observed
                      .map(AuthenticationAccountPersistence.AccountSnapshot::passwordVerifier)
                      .orElse(null));
          if (!verified || observed.isEmpty()) {
            throw new AuthenticationFailedException();
          }
          return publishPassword(transport, observed.orElseThrow(), false);
        });
  }

  /**
   * The account selector comes only from the current full session; preserve original login expiry.
   */
  public PublicKeyCredentialRequestOptions stepUpPassword(
      AuthenticationSessionTransport transport, String password) {
    return publication.attempt(
        Event.STEP_UP,
        () -> {
          var observation = transport.observe().orElseThrow(AuthenticationFailedException::new);
          var parent = sessions.observe(observation);
          if (!"full".equals(parent.state())) {
            throw new AuthenticationFailedException();
          }
          var account = accounts.findAccount(parent.account());
          if (!passwords.matches(password, account.passwordVerifier())) {
            throw new AuthenticationFailedException();
          }
          return publishPassword(transport, account, true);
        });
  }

  /**
   * Complete a consumed same-account assertion, atomically updating counter and current full
   * authority.
   */
  public AuthenticationSessionTransport.Observation passkey(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> credential,
      boolean stepUp) {
    var purpose =
        stepUp ? AuthenticationChallengePurpose.STEP_UP : AuthenticationChallengePurpose.LOGIN;
    var event = stepUp ? Event.STEP_UP : Event.LOGIN;
    return publication.attempt(
        event,
        () -> {
          var observation = transport.observe().orElseThrow(AuthenticationFailedException::new);
          var snapshot = sessions.observe(observation);
          if (snapshot.account() == null) {
            throw new AuthenticationFailedException();
          }
          var account = accounts.findAccount(snapshot.account());
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof = passkeys.verifyAssertion(bound, receipt, credential);
          return publication.complete(
              event,
              () -> {
                var parent = sessions.lock(observation);
                var current = accounts.requireUnchanged(account);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    purpose,
                    BoundWebAuthnOperations.ProofKind.ASSERTION,
                    passkeys.origin());
                accounts.publishAssertion(current, proof);
                sessions.full(parent, transport);
                return transport.observe().orElseThrow(AuthenticationFailedException::new);
              });
        });
  }

  private PublicKeyCredentialRequestOptions publishPassword(
      AuthenticationSessionTransport transport,
      AuthenticationAccountPersistence.AccountSnapshot observed,
      boolean stepUp) {
    var bound = passkeys.account(observed, accounts.credentials(observed.account()));
    var options = bound.assertionOptions(null);
    return publication.mutate(
        () -> {
          var parent =
              sessions.lock(
                  transport.observe().orElseThrow(AuthenticationFailedException::new),
                  observed.account());
          var current = accounts.requireUnchanged(observed);
          var reference = sessions.password(parent, current, transport, stepUp);
          challenges.issue(
              reference,
              stepUp
                  ? AuthenticationChallengePurpose.STEP_UP
                  : AuthenticationChallengePurpose.LOGIN,
              options.getChallenge().getBytes(),
              null);
          return options;
        });
  }
}
