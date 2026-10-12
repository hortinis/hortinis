package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorRecoveryPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/** Host-ticket-controlled replacement of both factors, followed by ordinary both-factor login. */
public final class AuthenticationOperatorRecoveryService {
  private final AuthenticationPublication publication;
  private final AuthenticationOperatorRecoveryPersistence recovery;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationChallengePersistence challenges;
  private final PasswordPolicy policy;
  private final PasswordVerifier passwords;
  private final AuthenticationPasskeys passkeys;

  /** No host command or runtime HTTP path is installed by constructing the private service. */
  public AuthenticationOperatorRecoveryService(
      AuthenticationPublication publication,
      AuthenticationOperatorRecoveryPersistence recovery,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    this.publication = publication;
    this.recovery = recovery;
    this.accounts = accounts;
    this.sessions = sessions;
    this.challenges = challenges;
    this.policy = policy;
    this.passwords = passwords;
    this.passkeys = passkeys;
  }

  /**
   * The owner chooses a compliant password; the operator-issued ticket grants no ordinary session.
   */
  public PublicKeyCredentialCreationOptions begin(
      AuthenticationSessionTransport transport, String ticket, String newPassword) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          var account = recovery.target(ticket);
          if (!policy.acceptsNewPassword(newPassword, account.login())) {
            throw new AuthenticationFailedException();
          }
          String hash = passwords.encode(newPassword);
          return publication.mutate(
              () -> {
                var reference = recovery.begin(transport, account, ticket, hash);
                var bound = passkeys.account(account, accounts.credentials(account.account()));
                var options = passkeys.creation(account.login(), bound, null);
                challenges.issue(
                    reference,
                    AuthenticationChallengePurpose.OPERATOR,
                    options.getChallenge().getBytes(),
                    null);
                return options;
              });
        });
  }

  /** Exact current ticket and actual verified registration precede every factor/epoch change. */
  public boolean complete(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey credential) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          var observation = transport.observe().orElseThrow(AuthenticationFailedException::new);
          var snapshot = sessions.observe(observation);
          if (!"recovery".equals(snapshot.state()) || !"operator".equals(snapshot.recoveryKind())) {
            throw new AuthenticationFailedException();
          }
          var account = accounts.findAccount(snapshot.account());
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof =
              bound.verifyRegistration(
                  passkeys.registration(account.login(), bound, receipt, credential));
          return publication.complete(
              Event.RECOVERY,
              () -> {
                var parent = sessions.lock(observation);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    AuthenticationChallengePurpose.OPERATOR,
                    BoundWebAuthnOperations.ProofKind.REGISTRATION,
                    passkeys.origin());
                return recovery.complete(parent, account, proof, transport);
              });
        });
  }
}
