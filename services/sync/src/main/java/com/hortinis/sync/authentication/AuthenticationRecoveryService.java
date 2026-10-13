package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountSecurityPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import java.util.List;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/** Two-proof restricted recovery; no entry point publishes full garden authority. */
public final class AuthenticationRecoveryService {
  private static final String PASSWORD_RECOVERY = "password";
  private static final String PASSKEY_RECOVERY = "passkey";
  private final AuthenticationPublication publication;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationChallengePersistence challenges;
  private final AuthenticationRecoveryCodePersistence codes;
  private final AuthenticationAccountSecurityPersistence security;
  private final PasswordPolicy policy;
  private final PasswordVerifier passwords;
  private final AuthenticationPasskeys passkeys;

  /** Shares the current account locks, crypto providers and explicit publication transaction. */
  public AuthenticationRecoveryService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      AuthenticationRecoveryCodePersistence codes,
      AuthenticationAccountSecurityPersistence security,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    this.publication = publication;
    this.accounts = accounts;
    this.sessions = sessions;
    this.challenges = challenges;
    this.codes = codes;
    this.security = security;
    this.policy = policy;
    this.passwords = passwords;
    this.passkeys = passkeys;
  }

  /** Same options and limited state for unknown, disabled, expired, used or incorrect codes. */
  public PublicKeyCredentialRequestOptions forgottenPassword(
      AuthenticationSessionTransport transport, String requestedLogin, String recoveryCode) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          String canonical = PasswordPolicy.canonicalLogin(requestedLogin);
          if (!AuthenticationSecrets.isRandomValue(recoveryCode)) {
            throw new AuthenticationFailedException();
          }
          var options = passkeys.recoveryOptions(canonical);
          return publication.mutate(
              () -> {
                var parent = sessions.lock(observe(transport));
                var reference = sessions.pendingRecovery(parent, canonical, transport);
                challenges.issue(
                    reference,
                    AuthenticationChallengePurpose.RECOVER_PASSWORD,
                    options.getChallenge().getBytes(),
                    AuthenticationSecrets.digest(recoveryCode));
                return options;
              });
        });
  }

  /** Commit same-account UV proof, code deletion and reset-only rotation together. */
  public boolean passwordProof(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          if (!"recovery".equals(snapshot.state())
              || !PASSWORD_RECOVERY.equals(snapshot.recoveryKind())
              || snapshot.account() != null
              || snapshot.bothFactorsAt() != null) {
            throw new AuthenticationFailedException();
          }
          var account =
              accounts
                  .findLogin(snapshot.stagedLogin())
                  .orElseThrow(AuthenticationFailedException::new);
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof = bound.verifyAssertion(passkeys.assertion(bound, receipt, credential));
          return publication.complete(
              Event.RECOVERY,
              () -> {
                var parent = sessions.lock(observation, account.account());
                var current = accounts.requireUnchanged(account);
                if (!current.login().equals(parent.stagedLogin())) {
                  throw new AuthenticationFailedException();
                }
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    AuthenticationChallengePurpose.RECOVER_PASSWORD,
                    BoundWebAuthnOperations.ProofKind.ASSERTION,
                    passkeys.origin());
                accounts.publishAssertion(current, proof);
                if (!codes.consumeForPasswordReset(
                    current.account(), current.epoch(), receipt, proof)) {
                  throw new AuthenticationFailedException();
                }
                sessions.readyRecovery(parent, current, PASSWORD_RECOVERY, transport);
                return true;
              });
        });
  }

  /**
   * Compliant new password ends restricted reset; normal login must prove both new/current factors.
   */
  public boolean resetPassword(AuthenticationSessionTransport transport, String newPassword) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          var account = accounts.findAccount(snapshot.account());
          if (!policy.acceptsNewPassword(newPassword, account.login())) {
            throw new AuthenticationFailedException();
          }
          String hash = passwords.encode(newPassword);
          return publication.complete(
              Event.RECOVERY,
              () -> {
                var parent = sessions.lock(observation);
                sessions.requireRecovery(parent, PASSWORD_RECOVERY);
                var current = accounts.requireUnchanged(account);
                security.password(current, hash);
                security.invalidate(current, transport);
                return true;
              });
        });
  }

  /** Current password plus unused code commits before any replacement options are available. */
  public List<AuthenticationCredentialService.CredentialSummary> lostPasskey(
      AuthenticationSessionTransport transport,
      String requestedLogin,
      String password,
      String recoveryCode) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          String canonical = PasswordPolicy.canonicalLogin(requestedLogin);
          var observed = accounts.findLogin(canonical);
          boolean verified =
              passwords.matches(
                  password,
                  observed
                      .map(AuthenticationAccountPersistence.AccountSnapshot::passwordVerifier)
                      .orElse(null));
          if (!verified || observed.isEmpty()) {
            throw new AuthenticationFailedException();
          }
          var account = observed.orElseThrow();
          return publication.complete(
              Event.RECOVERY,
              () -> {
                var parent = sessions.lock(observe(transport), account.account());
                AuthenticationSessionPublicationPersistence.requireState(parent, "anonymous");
                var current = accounts.requireUnchanged(account);
                if (!codes.consume(current.account(), current.epoch(), recoveryCode)) {
                  throw new AuthenticationFailedException();
                }
                var result =
                    accounts.credentials(current.account()).stream()
                        .map(
                            key ->
                                new AuthenticationCredentialService.CredentialSummary(
                                    key.id(), key.label()))
                        .toList();
                sessions.readyRecovery(parent, current, PASSKEY_RECOVERY, transport);
                return result;
              });
        });
  }

  /** The selected key must currently belong to the limited ceremony's account. */
  public PublicKeyCredentialCreationOptions replacementOptions(
      AuthenticationSessionTransport transport, byte[] lostCredential) {
    return publication.attempt(
        Event.RECOVERY,
        () ->
            publication.mutate(
                () -> {
                  var parent = sessions.lock(observe(transport));
                  sessions.requireRecovery(parent, PASSKEY_RECOVERY);
                  var account = accounts.findAccount(parent.account());
                  var reference = sessions.replacement(parent, lostCredential, transport);
                  var bound = passkeys.account(account, accounts.credentials(account.account()));
                  var options = bound.creationOptions(null);
                  challenges.issue(
                      reference,
                      AuthenticationChallengePurpose.RECOVER_PASSKEY,
                      options.getChallenge().getBytes(),
                      null);
                  return options;
                }));
  }

  /** Registration replaces the owned lost key atomically and revokes every old authority. */
  public boolean replacePasskey(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey credential) {
    return publication.attempt(
        Event.RECOVERY,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          var account = accounts.findAccount(snapshot.account());
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof = bound.verifyRegistration(passkeys.registration(bound, receipt, credential));
          return publication.complete(
              Event.RECOVERY,
              () -> {
                var parent = sessions.lock(observation);
                sessions.requireRecovery(parent, PASSKEY_RECOVERY);
                var current = accounts.requireUnchanged(account);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    AuthenticationChallengePurpose.RECOVER_PASSKEY,
                    BoundWebAuthnOperations.ProofKind.REGISTRATION,
                    passkeys.origin());
                accounts.replaceCredential(current, parent.replacementCredential(), proof);
                security.invalidate(current, transport);
                return true;
              });
        });
  }

  private static AuthenticationSessionTransport.Observation observe(
      AuthenticationSessionTransport transport) {
    return transport.observe().orElseThrow(AuthenticationFailedException::new);
  }
}
