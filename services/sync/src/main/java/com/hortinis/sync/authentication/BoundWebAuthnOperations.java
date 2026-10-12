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
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
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
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.cbor.CBORFactory;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * Per-ceremony Spring adapter bound to one trusted account snapshot and consumed server challenge.
 */
public final class BoundWebAuthnOperations implements WebAuthnRelyingPartyOperations {
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
  private final List<CredentialRecord> credentials;
  private final Consumer<CredentialRecord> verifiedAssertion;
  private final CryptoWorkLimiter work;
  private final ObjectConverter converter;
  private final WebAuthnManager verifier;

  /** Inputs come from locked service state; no public request can choose the account or RP. */
  public BoundWebAuthnOperations(
      String publicOrigin,
      PublicKeyCredentialUserEntity expectedUser,
      List<CredentialRecord> currentCredentials,
      Consumer<CredentialRecord> verifiedAssertion,
      CryptoWorkLimiter work) {
    URI configured = requireOrigin(publicOrigin);
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
    credentials = currentCredentials.stream().map(this::snapshotCredential).toList();
    if (credentials.stream().map(CredentialRecord::getCredentialId).distinct().count()
        != credentials.size()) {
      throw new IllegalArgumentException("Invalid trusted passkey ceremony.");
    }
    this.verifiedAssertion = Objects.requireNonNull(verifiedAssertion);
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

  @Override
  public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
      PublicKeyCredentialCreationOptionsRequest request) {
    requireExpectedAuthentication(request.getAuthentication());
    var descriptors =
        credentials.stream()
            .map(
                credential -> {
                  requireOwnedCredential(credential);
                  return PublicKeyCredentialDescriptor.builder()
                      .id(copy(credential.getCredentialId()))
                      .build();
                })
            .toList();
    return PublicKeyCredentialCreationOptions.builder()
        .rp(rp)
        .user(user)
        .challenge(Bytes.random())
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

  @Override
  public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
      PublicKeyCredentialRequestOptionsRequest request) {
    requireExpectedAuthentication(request.getAuthentication());
    return PublicKeyCredentialRequestOptions.builder()
        .challenge(Bytes.random())
        .rpId(rp.getId())
        .timeout(TIMEOUT)
        .allowCredentials(List.of())
        .userVerification(UserVerificationRequirement.REQUIRED)
        .build();
  }

  @Override
  public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
    return verifyRegistration(request).credential();
  }

  /** Verifies registration without granting account/session authority or publishing any record. */
  public VerifiedCredential verifyRegistration(RelyingPartyRegistrationRequest request) {
    final PublicKeyCredentialCreationOptions options = request.getCreationOptions();
    PublicKeyCredential<AuthenticatorAttestationResponse> credential =
        request.getPublicKey().getCredential();
    requireCredentialEnvelope(credential);
    requireBytes(credential.getResponse().getClientDataJSON(), 1, MAX_CLIENT_DATA_BYTES);
    requireBytes(credential.getResponse().getAttestationObject(), 1, MAX_ATTESTATION_BYTES);
    requireBytes(options.getChallenge(), RANDOM_BYTES, RANDOM_BYTES);
    if (!rp.getId().equals(options.getRp().getId())
        || !MessageDigest.isEqual(user.getId().getBytes(), options.getUser().getId().getBytes())
        || options.getAuthenticatorSelection().getUserVerification()
            != UserVerificationRequirement.REQUIRED
        || options.getAuthenticatorSelection().getResidentKey() != ResidentKeyRequirement.REQUIRED
        || options.getAttestation() != AttestationConveyancePreference.NONE) {
      throw new AuthenticationFailedException();
    }
    String label = request.getPublicKey().getLabel();
    if (label != null
        && (!PasswordPolicy.isScalarText(label, 0, 64, 256) || label.indexOf(0) >= 0)) {
      throw new AuthenticationFailedException();
    }
    return work.execute(() -> verifyRegistrationMaterial(options, credential, label));
  }

  private VerifiedCredential verifyRegistrationMaterial(
      PublicKeyCredentialCreationOptions options,
      PublicKeyCredential<AuthenticatorAttestationResponse> credential,
      String label) {
    byte[] challenge = options.getChallenge().getBytes().clone();
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
              .anyMatch(existing -> existing.getCredentialId().equals(credential.getRawId()))) {
        throw new AuthenticationFailedException();
      }
      COSEKey key = attested.getCOSEKey();
      requireKey(key);
      byte[] cose = converter.getCborMapper().writeValueAsBytes(publicOnlyKey(key));
      if (cose.length > MAX_COSE_BYTES) {
        throw new AuthenticationFailedException();
      }
      CredentialRecord verified =
          ImmutableCredentialRecord.builder()
              .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
              .credentialId(copy(credential.getRawId()))
              .userEntityUserId(copy(user.getId()))
              .publicKey(new ImmutablePublicKeyCose(cose))
              .signatureCount(data.getSignCount())
              .uvInitialized(true)
              .transports(Set.of())
              .backupEligible(data.isFlagBE())
              .backupState(data.isFlagBS())
              .label(label)
              .build();
      return new VerifiedCredential(
          verified,
          challenge,
          origin,
          Math.toIntExact(key.getAlgorithm().getValue()),
          ProofKind.REGISTRATION);
    } catch (com.webauthn4j.verifier.exception.VerificationException
        | com.webauthn4j.converter.exception.DataConversionException
        | tools.jackson.core.JacksonException
        | IllegalArgumentException exception) {
      throw new AuthenticationFailedException();
    }
  }

  @Override
  public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
    VerifiedCredential proof = verifyAssertion(request);
    verifiedAssertion.accept(proof.credential());
    return user;
  }

  /** Returns a verified assertion; current-state publication is a separate locked workflow. */
  public VerifiedCredential verifyAssertion(RelyingPartyAuthenticationRequest request) {
    var options = request.getRequestOptions();
    var credential = request.getPublicKey();
    requireCredentialEnvelope(credential);
    requireBytes(options.getChallenge(), RANDOM_BYTES, RANDOM_BYTES);
    requireBytes(credential.getResponse().getClientDataJSON(), 1, MAX_CLIENT_DATA_BYTES);
    requireBytes(credential.getResponse().getAuthenticatorData(), 1, MAX_PROOF_BYTES);
    requireBytes(credential.getResponse().getSignature(), 1, MAX_PROOF_BYTES);
    requireBytes(credential.getResponse().getUserHandle(), RANDOM_BYTES, RANDOM_BYTES);
    if (!rp.getId().equals(options.getRpId())
        || options.getUserVerification() != UserVerificationRequirement.REQUIRED
        || (options.getAllowCredentials() != null && !options.getAllowCredentials().isEmpty())
        || !MessageDigest.isEqual(
            user.getId().getBytes(), credential.getResponse().getUserHandle().getBytes())) {
      throw new AuthenticationFailedException();
    }
    CredentialRecord current =
        credentials.stream()
            .filter(value -> value.getCredentialId().equals(credential.getRawId()))
            .findFirst()
            .orElseThrow(AuthenticationFailedException::new);
    requireOwnedCredential(current);
    byte[] challenge = options.getChallenge().getBytes().clone();
    return work.execute(
        () -> {
          try {
            requireBytes(new Bytes(current.getPublicKey().getBytes()), 1, MAX_COSE_BYTES);
            COSEKey key =
                converter
                    .getCborMapper()
                    .readValue(current.getPublicKey().getBytes(), COSEKey.class);
            requireKey(key);
            var record =
                new CredentialRecordImpl(
                    new NoneAttestationStatement(),
                    true,
                    current.isBackupEligible(),
                    current.isBackupState(),
                    current.getSignatureCount(),
                    new AttestedCredentialData(
                        AAGUID.ZERO, current.getCredentialId().getBytes(), key),
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
                current.isBackupEligible()
                    ? Math.max(current.getSignatureCount(), data.getSignCount())
                    : data.getSignCount();
            CredentialRecord verified =
                ImmutableCredentialRecord.builder()
                    .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                    .credentialId(copy(current.getCredentialId()))
                    .userEntityUserId(copy(user.getId()))
                    .publicKey(
                        new ImmutablePublicKeyCose(current.getPublicKey().getBytes().clone()))
                    .signatureCount(counter)
                    .uvInitialized(true)
                    .transports(Set.of())
                    .backupEligible(current.isBackupEligible())
                    .backupState(data.isFlagBS())
                    .label(current.getLabel())
                    .build();
            return new VerifiedCredential(
                verified,
                challenge,
                origin,
                Math.toIntExact(key.getAlgorithm().getValue()),
                ProofKind.ASSERTION);
          } catch (com.webauthn4j.verifier.exception.VerificationException
              | com.webauthn4j.converter.exception.DataConversionException
              | tools.jackson.core.JacksonException
              | IllegalArgumentException exception) {
            throw new AuthenticationFailedException();
          }
        });
  }

  private void requireExpectedAuthentication(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !user.getName().equals(authentication.getName())) {
      throw new AuthenticationFailedException();
    }
  }

  private void requireOwnedCredential(CredentialRecord credential) {
    requireBytes(credential.getUserEntityUserId(), RANDOM_BYTES, RANDOM_BYTES);
    requireBytes(credential.getCredentialId(), 1, MAX_CREDENTIAL_BYTES);
    if (credential.getCredentialType() != PublicKeyCredentialType.PUBLIC_KEY
        || !credential.isUvInitialized()
        || !MessageDigest.isEqual(
            credential.getUserEntityUserId().getBytes(), user.getId().getBytes())
        || credential.getSignatureCount() < 0
        || credential.getSignatureCount() > MAX_COUNTER
        || credential.isBackupState() && !credential.isBackupEligible()) {
      throw new AuthenticationFailedException();
    }
  }

  private CredentialRecord snapshotCredential(CredentialRecord credential) {
    requireOwnedCredential(credential);
    requireBytes(new Bytes(credential.getPublicKey().getBytes()), 1, MAX_COSE_BYTES);
    return copyCredential(credential);
  }

  private static CredentialRecord copyCredential(CredentialRecord credential) {
    return ImmutableCredentialRecord.builder()
        .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
        .credentialId(copy(credential.getCredentialId()))
        .userEntityUserId(copy(credential.getUserEntityUserId()))
        .publicKey(new ImmutablePublicKeyCose(credential.getPublicKey().getBytes().clone()))
        .signatureCount(credential.getSignatureCount())
        .uvInitialized(true)
        .transports(Set.of())
        .backupEligible(credential.isBackupEligible())
        .backupState(credential.isBackupState())
        .label(credential.getLabel())
        .build();
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
    private final CredentialRecord credentialSnapshot;
    private final byte[] serverChallenge;
    private final String configuredOrigin;
    private final int selectedAlgorithm;
    private final ProofKind proofKind;

    private VerifiedCredential(
        CredentialRecord credential,
        byte[] challenge,
        String origin,
        int algorithm,
        ProofKind kind) {
      this.credentialSnapshot = copyCredential(credential);
      this.serverChallenge = challenge.clone();
      this.configuredOrigin = origin;
      this.selectedAlgorithm = algorithm;
      this.proofKind = kind;
    }

    /** Copies the minimized verified record; callers cannot mutate the retained proof. */
    public CredentialRecord credential() {
      return copyCredential(credentialSnapshot);
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
      return selectedAlgorithm;
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

  /** Canonical trusted configuration shared by verification and publication; never request text. */
  public static String canonicalPublicOrigin(String value) {
    return requireOrigin(value).toASCIIString();
  }

  private static URI requireOrigin(String value) {
    if (value == null) {
      throw new IllegalArgumentException("A trusted HTTPS WebAuthn origin is required.");
    }
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || host == null
          || host.contains(":")
          || host.endsWith(".")
          || host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
          || uri.getUserInfo() != null
          || !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || uri.getRawAuthority().endsWith(":")) {
        throw new IllegalArgumentException("A trusted HTTPS WebAuthn origin is required.");
      }
      return new URI(
          "https",
          null,
          host.toLowerCase(Locale.ROOT),
          uri.getPort() == 443 ? -1 : uri.getPort(),
          "",
          null,
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("A trusted HTTPS WebAuthn origin is required.");
    }
  }
}
