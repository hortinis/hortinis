package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountSecurityPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import java.util.List;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/** Account factor management and restricted recovery; changes require a subsequent normal login. */
public final class AuthenticationAccountService {
  private static final int MAX_CREDENTIALS = 8;
  private static final String RECOVERY_STATE = "recovery";
  private static final String PASSWORD_RECOVERY = "password";
  private static final String PASSKEY_RECOVERY = "passkey";
  private final AuthenticationPublication publication;
  private final AuthenticationAccountPersistence accounts;
  private final AuthenticationSessionPublicationPersistence sessions;
  private final AuthenticationChallengePersistence challenges;
  private final AuthenticationAccountSecurityPersistence security;
  private final AuthenticationRecoveryCodePersistence codes;
  private final CurrentAuthenticationAuthorityPersistence authority;
  private final PasswordPolicy policy;
  private final PasswordVerifier passwords;
  private final AuthenticationPasskeys passkeys;

  /** Shares established providers and explicit transaction components; installs no HTTP route. */
  public AuthenticationAccountService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      AuthenticationAccountSecurityPersistence security,
      AuthenticationRecoveryCodePersistence codes,
      CurrentAuthenticationAuthorityPersistence authority,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    this.publication = publication;
    this.accounts = accounts;
    this.sessions = sessions;
    this.challenges = challenges;
    this.security = security;
    this.codes = codes;
    this.authority = authority;
    this.policy = policy;
    this.passwords = passwords;
    this.passkeys = passkeys;
  }

  /** Read only minimized credential identifiers/labels under current account authority. */
  public List<CredentialSummary> list(AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          var parent = sessions.lock(observe(transport));
          if (RECOVERY_STATE.equals(parent.state())) {
            sessions.requireRecovery(parent, "passkey");
          } else {
            sessions.requireFull(parent);
          }
          var account = accounts.findAccount(parent.account());
          return credentialSummaries(account);
        });
  }

  /** Reload-safe availability requires full authority and never renews interaction time. */
  public AuthenticationRecoveryCodePersistence.CodeStatus recoveryCodeStatus(
      AuthenticationSessionTransport transport) {
    return publication.mutate(
        () -> {
          var parent = sessions.lock(observe(transport));
          sessions.requireFull(parent);
          return codes.status(parent.account());
        });
  }

  /**
   * Hash/verify outside locks, then recheck exact snapshot and recent normal authority at change.
   */
  public boolean password(
      AuthenticationSessionTransport transport, String currentPassword, String newPassword) {
    return changePassword(transport, currentPassword, newPassword, false);
  }

  private boolean changePassword(
      AuthenticationSessionTransport transport,
      String currentPassword,
      String newPassword,
      boolean recovering) {
    var event = recovering ? Event.RECOVERY : Event.CREDENTIAL_CHANGE;
    return publication.attempt(
        event,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          if (!recovering && !"full".equals(snapshot.state())) {
            throw new AuthenticationFailedException();
          }
          var account = accounts.findAccount(snapshot.account());
          if (!policy.acceptsNewPassword(newPassword, account.login())) {
            throw new AuthenticationFailedException();
          }
          if (!recovering && !passwords.matches(currentPassword, account.passwordVerifier())) {
            throw new AuthenticationFailedException();
          }
          String hash = passwords.encode(newPassword);
          return publication.complete(
              event,
              () -> {
                var parent = sessions.lock(observation);
                requireFactorChange(parent, recovering, PASSWORD_RECOVERY);
                var current = accounts.requireUnchanged(account);
                security.password(current, hash);
                security.invalidate(current, transport);
                return true;
              });
        });
  }

  /** Create a bounded registration challenge only under recent full normal authentication. */
  public PublicKeyCredentialCreationOptions options(AuthenticationSessionTransport transport) {
    return publication.attempt(
        Event.CREDENTIAL_CHANGE,
        () ->
            publication.mutate(
                () -> {
                  var parent = sessions.lock(observe(transport));
                  sessions.requireRecent(parent);
                  var account = accounts.findAccount(parent.account());
                  var credentials = accounts.credentials(account.account());
                  if (credentials.size() >= MAX_CREDENTIALS) {
                    throw new AuthenticationPreconditionException();
                  }
                  var bound = passkeys.account(account, credentials);
                  var options = bound.creationOptions(null);
                  challenges.issue(
                      parent.reference(),
                      AuthenticationChallengePurpose.CREDENTIAL,
                      options.getChallenge().getBytes(),
                      null);
                  return options;
                }));
  }

  /**
   * Consumed receipt is supplied by the pre-body HTTP guard; registration never grants new full
   * authority.
   */
  public boolean register(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey credential) {
    return register(transport, receipt, credential, false);
  }

  private boolean register(
      AuthenticationSessionTransport transport,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey credential,
      boolean recovering) {
    var event = recovering ? Event.RECOVERY : Event.CREDENTIAL_CHANGE;
    var purpose =
        recovering
            ? AuthenticationChallengePurpose.RECOVER_PASSKEY
            : AuthenticationChallengePurpose.CREDENTIAL;
    return publication.attempt(
        event,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          var account = accounts.findAccount(snapshot.account());
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof = passkeys.verifyRegistration(bound, receipt, credential);
          return publication.complete(
              event,
              () -> {
                var parent = sessions.lock(observation);
                requireFactorChange(parent, recovering, PASSKEY_RECOVERY);
                var current = accounts.requireUnchanged(account);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    purpose,
                    BoundWebAuthnOperations.ProofKind.REGISTRATION,
                    passkeys.origin());
                if (recovering) {
                  accounts.replaceCredential(current, parent.replacementCredential(), proof);
                } else {
                  accounts.additionalCredential(current, proof);
                }
                security.invalidate(current, transport);
                return true;
              });
        });
  }

  private void requireFactorChange(AuthenticationSession parent, boolean recovering, String kind) {
    if (recovering) {
      sessions.requireRecovery(parent, kind);
    } else {
      sessions.requireRecent(parent);
    }
  }

  /**
   * Same-account target selection and last-key protection hold before epoch/session invalidation.
   */
  public boolean revoke(AuthenticationSessionTransport transport, byte[] credentialId) {
    return publication.attempt(
        Event.CREDENTIAL_CHANGE,
        () ->
            publication.complete(
                Event.CREDENTIAL_CHANGE,
                () -> {
                  var parent = sessions.lock(observe(transport));
                  sessions.requireRecent(parent);
                  var account = accounts.findAccount(parent.account());
                  accounts.revokeCredential(account, credentialId);
                  security.invalidate(account, transport);
                  return true;
                }));
  }

  /** Replace the fixed code set and invalidate any old code-authorized restricted ceremony. */
  public AuthenticationRecoveryCodePersistence.IssuedCodes recoveryCodes(
      AuthenticationSessionTransport transport) {
    return publication.attempt(
        Event.CREDENTIAL_CHANGE,
        () ->
            publication.complete(
                Event.CREDENTIAL_CHANGE,
                () -> {
                  var observation = observe(transport);
                  var parent = sessions.lock(observation);
                  sessions.requireRecent(parent);
                  var account = accounts.findAccount(parent.account());
                  var full =
                      authority
                          .findFull(observation.verifier(), parent.reference())
                          .orElseThrow(AuthenticationFailedException::new);
                  var issued = codes.regenerate(full);
                  security.invalidate(account, transport);
                  return issued;
                }));
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
          var proof = passkeys.verifyAssertion(bound, receipt, credential);
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
    return changePassword(transport, null, newPassword, true);
  }

  /** Current password plus unused code commits before any replacement options are available. */
  public List<CredentialSummary> lostPasskey(
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
                var result = credentialSummaries(current);
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
    return register(transport, receipt, credential, true);
  }

  private List<CredentialSummary> credentialSummaries(
      AuthenticationAccountPersistence.AccountSnapshot account) {
    return accounts.credentials(account.account()).stream()
        .map(credential -> new CredentialSummary(credential.id(), credential.label()))
        .toList();
  }

  private static AuthenticationSessionTransport.Observation observe(
      AuthenticationSessionTransport transport) {
    return transport.observe().orElseThrow(AuthenticationFailedException::new);
  }

  /**
   * Public listing projection contains no public key, counter, device/model or attestation
   * metadata.
   */
  public record CredentialSummary(byte[] id, String label) {
    /** Copy the identifier so consumers cannot change its retained value. */
    public CredentialSummary {
      id = id.clone();
    }

    @Override
    public byte[] id() {
      return id.clone();
    }

    @Override
    public String toString() {
      return "CredentialSummary[redacted]";
    }
  }
}
