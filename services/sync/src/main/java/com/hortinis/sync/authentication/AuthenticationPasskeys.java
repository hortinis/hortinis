package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;

/**
 * Replaceable per-ceremony WebAuthn adapter constructed only from current server-owned snapshots.
 */
public final class AuthenticationPasskeys {
  private final String publicOrigin;
  private final CryptoWorkLimiter work;

  /** Configuration comes from the operator's trusted HTTPS origin, never request headers. */
  public AuthenticationPasskeys(String publicOrigin, CryptoWorkLimiter work) {
    this.publicOrigin = BoundWebAuthnOperations.canonicalPublicOrigin(publicOrigin);
    this.work = work;
  }

  /** Canonical configured origin for final proof publication comparison. */
  public String origin() {
    return publicOrigin;
  }

  /**
   * Pending discoverable options disclose no account lookup, stored key or recovery-code validity.
   */
  public PublicKeyCredentialRequestOptions recoveryOptions(String requestedLogin) {
    byte[] handle = java.util.Base64.getUrlDecoder().decode(AuthenticationSecrets.randomValue());
    return assertionOptions(requestedLogin, bound(requestedLogin, handle, List.of()), null);
  }

  /** Setup has no account, registered key or full authentication identity yet. */
  public BoundWebAuthnOperations setup(AuthenticationSessionPublicationPersistence.Parent parent) {
    if (!"setup".equals(parent.state())) {
      throw new AuthenticationFailedException();
    }
    return bound(parent.stagedLogin(), parent.stagedHandle(), List.of());
  }

  /**
   * Reconstructs only public verification material; attestation and transports are never retained.
   */
  public BoundWebAuthnOperations account(
      AuthenticationAccountPersistence.AccountSnapshot account,
      List<AuthenticationAccountPersistence.CredentialSnapshot> snapshots) {
    List<CredentialRecord> credentials =
        snapshots.stream()
            .map(
                snapshot ->
                    (CredentialRecord)
                        ImmutableCredentialRecord.builder()
                            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                            .credentialId(new Bytes(snapshot.id()))
                            .userEntityUserId(new Bytes(account.userHandle()))
                            .publicKey(new ImmutablePublicKeyCose(snapshot.publicKey()))
                            .signatureCount(snapshot.counter())
                            .uvInitialized(true)
                            .backupEligible(snapshot.backupEligible())
                            .backupState(snapshot.backedUp())
                            .transports(Set.of())
                            .label(snapshot.label())
                            .build())
            .toList();
    return bound(account.login(), account.userHandle(), credentials);
  }

  /**
   * Recreates server policy options; only the consumed server nonce is reused on proof requests.
   */
  public PublicKeyCredentialCreationOptions creation(
      String login, BoundWebAuthnOperations bound, byte[] nonce) {
    var base =
        bound.createPublicKeyCredentialCreationOptions(
            () -> UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
    if (nonce == null) {
      return base;
    }
    return PublicKeyCredentialCreationOptions.builder()
        .rp(base.getRp())
        .user(base.getUser())
        .challenge(new Bytes(nonce.clone()))
        .pubKeyCredParams(base.getPubKeyCredParams())
        .timeout(base.getTimeout())
        .excludeCredentials(base.getExcludeCredentials())
        .authenticatorSelection(base.getAuthenticatorSelection())
        .attestation(base.getAttestation())
        .build();
  }

  /** Reconstructs discoverable assertion policy without trusting request-supplied options. */
  public PublicKeyCredentialRequestOptions assertionOptions(
      String login, BoundWebAuthnOperations bound, byte[] nonce) {
    var base =
        bound.createCredentialRequestOptions(
            () -> UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
    if (nonce == null) {
      return base;
    }
    return PublicKeyCredentialRequestOptions.builder()
        .challenge(new Bytes(nonce.clone()))
        .rpId(base.getRpId())
        .timeout(base.getTimeout())
        .allowCredentials(List.of())
        .userVerification(base.getUserVerification())
        .build();
  }

  /** Registration proof options are derived solely from the trusted setup and consumed receipt. */
  public RelyingPartyRegistrationRequest registration(
      String login,
      BoundWebAuthnOperations bound,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey key) {
    if (receipt == null) {
      throw new AuthenticationFailedException();
    }
    var options = creation(login, bound, receipt.challenge());
    return new RelyingPartyRegistrationRequest() {
      @Override
      public PublicKeyCredentialCreationOptions getCreationOptions() {
        return options;
      }

      @Override
      public RelyingPartyPublicKey getPublicKey() {
        return key;
      }
    };
  }

  /** Assertion proof options derive solely from current ownership and the consumed server nonce. */
  public RelyingPartyAuthenticationRequest assertion(
      String login,
      BoundWebAuthnOperations bound,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
    if (receipt == null) {
      throw new AuthenticationFailedException();
    }
    return new RelyingPartyAuthenticationRequest(
        assertionOptions(login, bound, receipt.challenge()), credential);
  }

  private BoundWebAuthnOperations bound(
      String login, byte[] handle, List<CredentialRecord> credentials) {
    var user =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(new Bytes(handle))
            .name(login)
            .displayName("Hortinis account")
            .build();
    return new BoundWebAuthnOperations(
        publicOrigin,
        user,
        credentials,
        ignored -> {
          throw new IllegalStateException("Assertion publication requires a current transaction.");
        },
        work);
  }
}
