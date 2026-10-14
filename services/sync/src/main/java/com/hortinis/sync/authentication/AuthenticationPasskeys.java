package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import java.util.List;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;

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
    return bound(requestedLogin, handle, List.of()).assertionOptions(null);
  }

  /** Setup has no account, registered key or full authentication identity yet. */
  public BoundWebAuthnOperations setup(AuthenticationSession parent) {
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
      List<AuthenticationCredential> snapshots) {
    return bound(account.login(), account.userHandle(), snapshots);
  }

  /** Verifies enrollment directly against the consumed server nonce and the bound policy. */
  public BoundWebAuthnOperations.VerifiedCredential verifyRegistration(
      BoundWebAuthnOperations bound,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      RelyingPartyPublicKey key) {
    return bound.verifyRegistration(challenge(receipt), key);
  }

  /** Verifies assertion directly against the consumed server nonce and current account keys. */
  public BoundWebAuthnOperations.VerifiedCredential verifyAssertion(
      BoundWebAuthnOperations bound,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
    return bound.verifyAssertion(challenge(receipt), credential);
  }

  private static byte[] challenge(AuthenticationChallengePersistence.ConsumedChallenge receipt) {
    if (receipt == null) {
      throw new AuthenticationFailedException();
    }
    return receipt.challenge();
  }

  private BoundWebAuthnOperations bound(
      String login, byte[] handle, List<AuthenticationCredential> credentials) {
    var user =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(new Bytes(handle))
            .name(login)
            .displayName("Hortinis account")
            .build();
    return new BoundWebAuthnOperations(publicOrigin, user, credentials, work);
  }
}
