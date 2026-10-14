package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSetupPersistence;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/**
 * Controlled sole-account setup; completion invalidates setup and requires a normal two-factor
 * login.
 */
public final class AuthenticationSetupService {
  private final AuthenticationPublication publication;
  private final AuthenticationSetupPersistence setup;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationChallengePersistence challenges;
  private final PasswordPolicy policy;
  private final PasswordVerifier passwords;
  private final AuthenticationPasskeys passkeys;

  /** No HTTP or runtime mode is installed by constructing this feature entry point. */
  public AuthenticationSetupService(
      AuthenticationPublication publication,
      AuthenticationSetupPersistence setup,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    this.publication = publication;
    this.setup = setup;
    this.sessions = sessions;
    this.challenges = challenges;
    this.policy = policy;
    this.passwords = passwords;
    this.passkeys = passkeys;
  }

  /** Validates and hashes outside publication locks; a host ticket alone grants no authority. */
  public PublicKeyCredentialCreationOptions begin(
      AuthenticationSessionTransport transport,
      String ticket,
      String requestedLogin,
      String password) {
    return publication.attempt(
        Event.SETUP,
        () -> {
          String login = PasswordPolicy.canonicalLogin(requestedLogin);
          if (!policy.acceptsNewPassword(password, login)) {
            throw new AuthenticationFailedException();
          }
          String hash = passwords.encode(password);
          return publication.mutate(
              () -> {
                var reference = setup.begin(transport, ticket, login, hash);
                var parent =
                    sessions.observe(
                        transport.observe().orElseThrow(AuthenticationFailedException::new));
                var options = passkeys.setup(parent).creationOptions(null);
                challenges.issue(
                    reference,
                    AuthenticationChallengePurpose.SETUP,
                    options.getChallenge().getBytes(),
                    null);
                return options;
              });
        });
  }

  /** The HTTP boundary consumes the nonce before body parsing and supplies its trusted receipt. */
  public AuthenticationRecoveryCodePersistence.IssuedCodes complete(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey credential) {
    return publication.attempt(
        Event.SETUP,
        () -> {
          var observation = transport.observe().orElseThrow(AuthenticationFailedException::new);
          var snapshot = sessions.observe(observation);
          var bound = passkeys.setup(snapshot);
          var proof = passkeys.verifyRegistration(bound, receipt, credential);
          return publication.complete(
              Event.SETUP,
              () -> {
                var parent = sessions.lock(observation);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    AuthenticationChallengePurpose.SETUP,
                    BoundWebAuthnOperations.ProofKind.REGISTRATION,
                    passkeys.origin());
                return setup.complete(parent, proof, transport);
              });
        });
  }
}
