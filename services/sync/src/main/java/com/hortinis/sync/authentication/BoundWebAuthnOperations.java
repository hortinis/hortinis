package com.hortinis.sync.authentication;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.authenticator.Curve;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey;
import com.webauthn4j.data.attestation.authenticator.RSACOSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import com.webauthn4j.server.ServerProperty;
import java.net.URI;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.cbor.CBORFactory;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * Per-ceremony Spring adapter bound to one trusted account snapshot and consumed server challenge.
 */
public final class BoundWebAuthnOperations {
  private static final int RANDOM_BYTES = 32;
  private static final int MAX_CREDENTIAL_BYTES = 1536;
  private static final int MAX_CLIENT_DATA_BYTES = 3072;
  private static final int MAX_ATTESTATION_BYTES = 32768;
  private static final int MAX_PROOF_BYTES = 768;
  private static final int MAX_COSE_BYTES = 2048;
  private static final int MAX_CREDENTIALS = 8;
  private static final long MAX_COUNTER = 4294967295L;
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final String origin;
  private final PublicKeyCredentialRpEntity rp;
  private final PublicKeyCredentialUserEntity user;
  private final List<AuthenticationCredential> credentials;
  private final CryptoWorkLimiter work;
  private final ObjectConverter converter;
  private final WebAuthnManager verifier;

  /** Inputs come from locked service state; no public request can choose the account or RP. */
  public BoundWebAuthnOperations(
      String publicOrigin,
      PublicKeyCredentialUserEntity expectedUser,
      List<AuthenticationCredential> currentCredentials,
      CryptoWorkLimiter work) {
    URI configured = TrustedApplicationOrigin.parse(publicOrigin);
    origin = configured.toASCIIString();
    rp = PublicKeyCredentialRpEntity.builder().id(configured.getHost()).name("Hortinis").build();
    requireBytes(expectedUser.getId(), RANDOM_BYTES, RANDOM_BYTES);
    String login = PasswordPolicy.canonicalLogin(expectedUser.getName());
    if (!login.equals(expectedUser.getName()) || currentCredentials.size() > MAX_CREDENTIALS) {
      throw new IllegalArgumentException("Invalid trusted passkey ceremony.");
    }
    user =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(copy(expectedUser.getId()))
            .name(login)
            .displayName("Hortinis account")
            .build();
    credentials = List.copyOf(currentCredentials);
    credentials.forEach(BoundWebAuthnOperations::requireCredential);
    if (credentials.stream().map(credential -> new Bytes(credential.id())).distinct().count()
        != credentials.size()) {
      throw new IllegalArgumentException("Invalid trusted passkey ceremony.");
    }
    this.work = Objects.requireNonNull(work);
    StreamReadConstraints jsonBounds =
        StreamReadConstraints.builder()
            .maxDocumentLength(MAX_CLIENT_DATA_BYTES)
            .maxNestingDepth(8)
            .maxTokenCount(128)
            .maxStringLength(MAX_CLIENT_DATA_BYTES)
            .build();
    StreamReadConstraints cborBounds =
        StreamReadConstraints.builder()
            .maxDocumentLength(MAX_ATTESTATION_BYTES)
            .maxNestingDepth(16)
            .maxTokenCount(2048)
            .maxStringLength(MAX_ATTESTATION_BYTES)
            .build();
    converter =
        new ObjectConverter(
            JsonMapper.builder(
                    JsonFactory.builder()
                        .streamReadConstraints(jsonBounds)
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .build(),
            CBORMapper.builder(
                    CBORFactory.builder()
                        .streamReadConstraints(cborBounds)
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .build());
    verifier = WebAuthnManager.createNonStrictWebAuthnManager(converter);
    var strictCounter = verifier.getAuthenticationDataVerifier().getMaliciousCounterValueHandler();
    verifier
        .getAuthenticationDataVerifier()
        .setMaliciousCounterValueHandler(
            proof -> {
              // The library has already verified UP/UV, unchanged backup eligibility and the
              // signature.
              // Synced credentials may have independent counters; one-use server challenge supplies
              // freshness.
              if (!Boolean.TRUE.equals(proof.getCredentialRecord().isBackupEligible())) {
                strictCounter.maliciousCounterValueDetected(proof);
              }
            });
  }

  /** Builds fixed registration policy using a new or consumed server-owned challenge. */
  public PublicKeyCredentialCreationOptions creationOptions(byte[] nonce) {
    var descriptors =
        credentials.stream()
            .map(
                credential -> {
                  return PublicKeyCredentialDescriptor.builder()
                      .id(new Bytes(credential.id()))
                      .build();
                })
            .toList();
    return PublicKeyCredentialCreationOptions.builder()
        .rp(rp)
        .user(user)
        .challenge(challenge(nonce))
        .pubKeyCredParams(
            org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters.ES256,
            org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters.RS256,
            org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters.EdDSA)
        .timeout(TIMEOUT)
        .excludeCredentials(descriptors)
        .authenticatorSelection(
            AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build())
        .attestation(AttestationConveyancePreference.NONE)
        .build();
  }

  /** Builds discoverable assertion policy for the account already bound to this adapter. */
  public PublicKeyCredentialRequestOptions assertionOptions(byte[] nonce) {
    return PublicKeyCredentialRequestOptions.builder()
        .challenge(challenge(nonce))
        .rpId(rp.getId())
        .timeout(TIMEOUT)
        .allowCredentials(List.of())
        .userVerification(UserVerificationRequirement.REQUIRED)
        .build();
  }

  /** Verifies registration without granting account/session authority or publishing any record. */
  public VerifiedCredential verifyRegistration(byte[] nonce, RelyingPartyPublicKey key) {
    final byte[] challenge = requiredChallenge(nonce);
    PublicKeyCredential<AuthenticatorAttestationResponse> credential = key.getCredential();
    requireCredentialEnvelope(credential);
    requireBytes(credential.getResponse().getClientDataJSON(), 1, MAX_CLIENT_DATA_BYTES);
    requireBytes(credential.getResponse().getAttestationObject(), 1, MAX_ATTESTATION_BYTES);
    String label = key.getLabel();
    if (label != null
        && (!PasswordPolicy.isScalarText(label, 0, 64, 256) || label.indexOf(0) >= 0)) {
      throw new AuthenticationFailedException();
    }
    return work.execute(() -> verifyRegistrationMaterial(challenge, credential, label));
  }

  private VerifiedCredential verifyRegistrationMaterial(
      byte[] challenge,
      PublicKeyCredential<AuthenticatorAttestationResponse> credential,
      String label) {
    try {
      RegistrationParameters parameters =
          new RegistrationParameters(
              serverProperty(new Bytes(challenge)),
              List.of(
                  parameter(COSEAlgorithmIdentifier.ES256),
                  parameter(COSEAlgorithmIdentifier.RS256),
                  parameter(COSEAlgorithmIdentifier.EdDSA)),
              true,
              true);
      byte[] attestationBytes = credential.getResponse().getAttestationObject().getBytes();
      byte[] clientDataBytes = credential.getResponse().getClientDataJSON().getBytes();
      RegistrationRequest registration =
          new RegistrationRequest(attestationBytes, clientDataBytes, Set.of());
      RegistrationData proof = verifier.verify(registration, parameters);
      AttestationObject attestation = proof.getAttestationObject();
      if (attestation == null) {
        throw new AuthenticationFailedException();
      }
      AuthenticatorData<RegistrationExtensionAuthenticatorOutput> data =
          attestation.getAuthenticatorData();
      AttestedCredentialData attested = data.getAttestedCredentialData();
      if (attested == null) {
        throw new AuthenticationFailedException();
      }
      if (!MessageDigest.isEqual(attested.getCredentialId(), credential.getRawId().getBytes())
          || credentials.stream()
              .anyMatch(
                  existing ->
                      MessageDigest.isEqual(existing.id(), credential.getRawId().getBytes()))) {
        throw new AuthenticationFailedException();
      }
      COSEKey key = attested.getCOSEKey();
      requireKey(key);
      byte[] cose = converter.getCborMapper().writeValueAsBytes(publicOnlyKey(key));
      if (cose.length > MAX_COSE_BYTES) {
        throw new AuthenticationFailedException();
      }
      var verified =
          new AuthenticationCredential(
              credential.getRawId().getBytes(),
              cose,
              Math.toIntExact(key.getAlgorithm().getValue()),
              data.getSignCount(),
              data.isFlagBE(),
              data.isFlagBS(),
              label);
      return new VerifiedCredential(
          verified, user.getId().getBytes(), challenge, origin, ProofKind.REGISTRATION);
    } catch (com.webauthn4j.verifier.exception.VerificationException
        | com.webauthn4j.converter.exception.DataConversionException
        | tools.jackson.core.JacksonException
        | IllegalArgumentException exception) {
      throw new AuthenticationFailedException();
    }
  }

  /** Returns a verified assertion; current-state publication is a separate locked workflow. */
  public VerifiedCredential verifyAssertion(
      byte[] nonce, PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
    final byte[] challenge = requiredChallenge(nonce);
    requireCredentialEnvelope(credential);
    requireBytes(credential.getResponse().getClientDataJSON(), 1, MAX_CLIENT_DATA_BYTES);
    requireBytes(credential.getResponse().getAuthenticatorData(), 1, MAX_PROOF_BYTES);
    requireBytes(credential.getResponse().getSignature(), 1, MAX_PROOF_BYTES);
    requireBytes(credential.getResponse().getUserHandle(), RANDOM_BYTES, RANDOM_BYTES);
    if (!MessageDigest.isEqual(
        user.getId().getBytes(), credential.getResponse().getUserHandle().getBytes())) {
      throw new AuthenticationFailedException();
    }
    AuthenticationCredential current =
        credentials.stream()
            .filter(value -> MessageDigest.isEqual(value.id(), credential.getRawId().getBytes()))
            .findFirst()
            .orElseThrow(AuthenticationFailedException::new);
    return work.execute(
        () -> {
          try {
            requireBytes(new Bytes(current.publicKey()), 1, MAX_COSE_BYTES);
            COSEKey key = converter.getCborMapper().readValue(current.publicKey(), COSEKey.class);
            requireKey(key);
            var record =
                new CredentialRecordImpl(
                    new NoneAttestationStatement(),
                    true,
                    current.backupEligible(),
                    current.backedUp(),
                    current.counter(),
                    new AttestedCredentialData(AAGUID.ZERO, current.id(), key),
                    null,
                    null,
                    null,
                    Set.of());
            AuthenticationData proof =
                verifier.verify(
                    new AuthenticationRequest(
                        credential.getRawId().getBytes(),
                        credential.getResponse().getAuthenticatorData().getBytes(),
                        credential.getResponse().getClientDataJSON().getBytes(),
                        credential.getResponse().getSignature().getBytes()),
                    new AuthenticationParameters(
                        serverProperty(new Bytes(challenge)), record, null, true, true));
            AuthenticatorData<AuthenticationExtensionAuthenticatorOutput> data =
                proof.getAuthenticatorData();
            if (data == null) {
              throw new AuthenticationFailedException();
            }
            long counter =
                current.backupEligible()
                    ? Math.max(current.counter(), data.getSignCount())
                    : data.getSignCount();
            var verified =
                new AuthenticationCredential(
                    current.id(),
                    current.publicKey(),
                    Math.toIntExact(key.getAlgorithm().getValue()),
                    counter,
                    current.backupEligible(),
                    data.isFlagBS(),
                    current.label());
            return new VerifiedCredential(
                verified, user.getId().getBytes(), challenge, origin, ProofKind.ASSERTION);
          } catch (com.webauthn4j.verifier.exception.VerificationException
              | com.webauthn4j.converter.exception.DataConversionException
              | tools.jackson.core.JacksonException
              | IllegalArgumentException exception) {
            throw new AuthenticationFailedException();
          }
        });
  }

  private static void requireCredential(AuthenticationCredential credential) {
    requireBytes(new Bytes(credential.id()), 1, MAX_CREDENTIAL_BYTES);
    requireBytes(new Bytes(credential.publicKey()), 1, MAX_COSE_BYTES);
    if (credential.counter() < 0
        || credential.counter() > MAX_COUNTER
        || credential.backedUp() && !credential.backupEligible()) {
      throw new AuthenticationFailedException();
    }
  }

  private static void requireCredentialEnvelope(PublicKeyCredential<?> credential) {
    requireBytes(credential.getRawId(), 1, MAX_CREDENTIAL_BYTES);
    if (credential.getType() != PublicKeyCredentialType.PUBLIC_KEY
        || !credential.getRawId().toBase64UrlString().equals(credential.getId())) {
      throw new AuthenticationFailedException();
    }
  }

  private static void requireBytes(Bytes bytes, int minimum, int maximum) {
    if (bytes == null || bytes.getBytes().length < minimum || bytes.getBytes().length > maximum) {
      throw new AuthenticationFailedException();
    }
  }

  private static byte[] requiredChallenge(byte[] nonce) {
    requireBytes(nonce == null ? null : new Bytes(nonce), RANDOM_BYTES, RANDOM_BYTES);
    return nonce.clone();
  }

  private static Bytes challenge(byte[] nonce) {
    Bytes value = nonce == null ? Bytes.random() : new Bytes(nonce.clone());
    requireBytes(value, RANDOM_BYTES, RANDOM_BYTES);
    return value;
  }

  private static Bytes copy(Bytes bytes) {
    return new Bytes(bytes.getBytes().clone());
  }

  private ServerProperty serverProperty(Bytes challenge) {
    return new ServerProperty(
        Set.of(new Origin(origin)), rp.getId(), new DefaultChallenge(challenge.getBytes()));
  }

  private static PublicKeyCredentialParameters parameter(COSEAlgorithmIdentifier algorithm) {
    return new PublicKeyCredentialParameters(
        com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY, algorithm);
  }

  private static COSEKey publicOnlyKey(COSEKey key) {
    // Rebuild from verified public material; discard optional key ID/operations/IV metadata.
    return switch (key) {
      case EC2COSEKey ec ->
          EC2COSEKey.create((ECPublicKey) ec.getPublicKey(), COSEAlgorithmIdentifier.ES256);
      case RSACOSEKey rsa ->
          RSACOSEKey.create((RSAPublicKey) rsa.getPublicKey(), COSEAlgorithmIdentifier.RS256);
      case EdDSACOSEKey ed ->
          EdDSACOSEKey.create((EdECPublicKey) ed.getPublicKey(), COSEAlgorithmIdentifier.EdDSA);
      default -> throw new AuthenticationFailedException();
    };
  }

  private static void requireKey(COSEKey key) {
    if (key == null || !key.hasPublicKey() || key.hasPrivateKey()) {
      throw new AuthenticationFailedException();
    }
    key.validate();
    boolean allowed =
        key instanceof EC2COSEKey ec
                && COSEAlgorithmIdentifier.ES256.equals(key.getAlgorithm())
                && ec.getCurve() == Curve.SECP256R1
            || key instanceof RSACOSEKey rsa
                && COSEAlgorithmIdentifier.RS256.equals(key.getAlgorithm())
                && ((RSAPublicKey) rsa.getPublicKey()).getModulus().bitLength() >= 2048
                && ((RSAPublicKey) rsa.getPublicKey()).getModulus().bitLength() <= 4096
            || key instanceof EdDSACOSEKey ed
                && COSEAlgorithmIdentifier.EdDSA.equals(key.getAlgorithm())
                && ed.getCurve() == Curve.ED25519;
    if (!allowed) {
      throw new AuthenticationFailedException();
    }
  }

  /** Registration is never an authentication assertion or a garden-authorized session. */
  public enum ProofKind {
    REGISTRATION,
    ASSERTION
  }

  /** Only successful verifier paths construct this internal immutable result. */
  public static final class VerifiedCredential {
    private final AuthenticationCredential credentialSnapshot;
    private final byte[] accountHandle;
    private final byte[] serverChallenge;
    private final String configuredOrigin;
    private final ProofKind proofKind;

    private VerifiedCredential(
        AuthenticationCredential credential,
        byte[] userHandle,
        byte[] challenge,
        String origin,
        ProofKind kind) {
      this.credentialSnapshot = credential;
      this.accountHandle = userHandle.clone();
      this.serverChallenge = challenge.clone();
      this.configuredOrigin = origin;
      this.proofKind = kind;
    }

    /** The verified record is immutable and contains only the persisted public key fields. */
    public AuthenticationCredential credential() {
      return credentialSnapshot;
    }

    /** Binds the verified credential to the account used by this ceremony. */
    public byte[] userHandle() {
      return accountHandle.clone();
    }

    /** Copies the exact verified server nonce for comparison to the consumed receipt. */
    public byte[] challenge() {
      return serverChallenge.clone();
    }

    /** Configured canonical origin; never a request Host/forwarding-derived value. */
    public String origin() {
      return configuredOrigin;
    }

    /** Selected verified COSE algorithm for constrained persistence. */
    public int algorithm() {
      return credentialSnapshot.algorithm();
    }

    /** Distinguishes enrollment from an actual signed authentication assertion. */
    public ProofKind kind() {
      return proofKind;
    }

    @Override
    public String toString() {
      return "VerifiedCredential[redacted]";
    }
  }
}
