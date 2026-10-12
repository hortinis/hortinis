package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountSecurityPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import java.util.List;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

/**
 * Protected credential management; every change revokes old authority and requires normal login.
 */
public final class AuthenticationCredentialService {
  private static final int MAX_CREDENTIALS = 8;
  private static final String RECOVERY_STATE = "recovery";
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
  public AuthenticationCredentialService(
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
          return accounts.credentials(account.account()).stream()
              .map(credential -> new CredentialSummary(credential.id(), credential.label()))
              .toList();
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
    return publication.attempt(
        Event.CREDENTIAL_CHANGE,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          if (!"full".equals(snapshot.state())) {
            throw new AuthenticationFailedException();
          }
          var account = accounts.findAccount(snapshot.account());
          if (!policy.acceptsNewPassword(newPassword, account.login())) {
            throw new AuthenticationFailedException();
          }
          if (!passwords.matches(currentPassword, account.passwordVerifier())) {
            throw new AuthenticationFailedException();
          }
          String hash = passwords.encode(newPassword);
          return publication.complete(
              Event.CREDENTIAL_CHANGE,
              () -> {
                var parent = sessions.lock(observation);
                sessions.requireRecent(parent);
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
                  var options = passkeys.creation(account.login(), bound, null);
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
    return publication.attempt(
        Event.CREDENTIAL_CHANGE,
        () -> {
          var observation = observe(transport);
          var snapshot = sessions.observe(observation);
          var account = accounts.findAccount(snapshot.account());
          var bound = passkeys.account(account, accounts.credentials(account.account()));
          var proof =
              bound.verifyRegistration(
                  passkeys.registration(account.login(), bound, receipt, credential));
          return publication.complete(
              Event.CREDENTIAL_CHANGE,
              () -> {
                var parent = sessions.lock(observation);
                sessions.requireRecent(parent);
                var current = accounts.requireUnchanged(account);
                publication.requireBound(
                    parent,
                    receipt,
                    proof,
                    AuthenticationChallengePurpose.CREDENTIAL,
                    BoundWebAuthnOperations.ProofKind.REGISTRATION,
                    passkeys.origin());
                accounts.additionalCredential(current, proof);
                security.invalidate(current, transport);
                return true;
              });
        });
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
