package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey;
import com.webauthn4j.data.attestation.authenticator.RSACOSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.COSEKeyOperation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import tools.jackson.databind.json.JsonMapper;

/** Fresh ephemeral keys and signed proofs; no private key or active credential is committed. */
class BoundWebAuthnOperationsTest {
  private static final String ED_ALGORITHM = "Ed25519";
  private static final String WEAK_RSA = "weak-rsa";
  private static final String RSA_ALGORITHM = "RSA";
  private static final String ORIGIN = "https://localhost:4200";
  private static final String LOGIN = "synthetic-gardener";
  private static final String AUTHENTICATOR_RP = "localhost";
  private static final int UP_UV = 0x05;
  private static final int ATTESTED = 0x40;
  private static final int BACKUP_ELIGIBLE = 0x08;
  private static final int BACKED_UP = 0x10;
  private final PublicKeyCredentialUserEntity user =
      ImmutablePublicKeyCredentialUserEntity.builder()
          .id(Bytes.random())
          .name(LOGIN)
          .displayName("Hortinis account")
          .build();
  private final AtomicReference<AuthenticationCredential> updated = new AtomicReference<>();

  @ParameterizedTest
  @ValueSource(strings = {"EC", RSA_ALGORITHM, ED_ALGORITHM})
  void verifiedResultsBindNonceOriginKindAndDoNotPublishAssertion(String algorithm)
      throws Exception {
    Material material = material(algorithm);
    BoundWebAuthnOperations registering = operations(List.of());
    var options = creation(registering);
    var enrollment =
        registrationProof(
            registering, registration(options, material, UP_UV | ATTESTED, 0, ORIGIN));
    assertThat(enrollment.kind()).isEqualTo(BoundWebAuthnOperations.ProofKind.REGISTRATION);
    assertThat(enrollment.origin()).isEqualTo(ORIGIN);
    assertThat(enrollment.challenge()).containsExactly(options.getChallenge().getBytes());
    int expectedAlgorithm =
        switch (algorithm) {
          case "EC" -> -7;
          case RSA_ALGORITHM -> -257;
          case ED_ALGORITHM -> -8;
          default -> throw new AssertionError("Unknown synthetic key algorithm.");
        };
    assertThat(enrollment.algorithm()).isEqualTo(expectedAlgorithm);
    byte[] changedNonce = enrollment.challenge();
    changedNonce[0] ^= 1;
    assertThat(enrollment.challenge()).containsExactly(options.getChallenge().getBytes());
    byte[] publicKey = enrollment.credential().publicKey().clone();
    enrollment.credential().publicKey()[0] ^= 1;
    enrollment.credential().id()[0] ^= 1;
    enrollment.userHandle()[0] ^= 1;
    assertThat(enrollment.credential().publicKey()).containsExactly(publicKey);
    assertThat(enrollment.credential().id()).containsExactly(material.id().getBytes());
    assertThat(enrollment.userHandle()).containsExactly(user.getId().getBytes());
    assertThat(enrollment.toString()).doesNotContain(material.id().toBase64UrlString(), LOGIN);
    byte[] snapshotId = enrollment.credential().id();
    byte[] snapshotKey = enrollment.credential().publicKey();
    var snapshot =
        new AuthenticationCredential(
            snapshotId, snapshotKey, expectedAlgorithm, 0, false, false, "");
    var stored = new java.util.ArrayList<>(List.of(snapshot));
    BoundWebAuthnOperations authenticating = operations(stored);
    snapshotId[0] ^= 1;
    snapshotKey[0] ^= 1;
    stored.clear();
    var requestOptions = assertionOptions(authenticating);
    var proof =
        assertionProof(
            authenticating,
            assertion(requestOptions, material, UP_UV, 1, ORIGIN, AUTHENTICATOR_RP, user.getId()));
    assertThat(proof.kind()).isEqualTo(BoundWebAuthnOperations.ProofKind.ASSERTION);
    assertThat(proof.challenge()).containsExactly(requestOptions.getChallenge().getBytes());
    assertThat(proof.origin()).isEqualTo(ORIGIN);
    assertThat(proof.algorithm()).isEqualTo(expectedAlgorithm);
    assertThat(proof.credential().counter()).isEqualTo(1);
    assertThat(updated.get()).isNull();
    assertThatThrownBy(
            () ->
                assertionProof(
                    authenticating,
                    assertion(
                        requestOptions,
                        material,
                        UP_UV,
                        1,
                        "https://other.example.test",
                        AUTHENTICATOR_RP,
                        user.getId())))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(updated.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"EC", RSA_ALGORITHM, ED_ALGORITHM})
  void verifiesEverySelectedAlgorithmAndKeepsOnlyNecessaryCredentialState(String algorithm)
      throws Exception {
    Material material = material(algorithm);
    BoundWebAuthnOperations registering = operations(List.of());
    var creation = creation(registering);
    assertThat(creation.getUser().getDisplayName()).isEqualTo("Hortinis account");
    assertThat(creation.getAuthenticatorSelection().getUserVerification())
        .isEqualTo(UserVerificationRequirement.REQUIRED);
    assertThat(creation.getAuthenticatorSelection().getResidentKey())
        .isEqualTo(ResidentKeyRequirement.REQUIRED);
    AuthenticationCredential credential =
        registrationProof(
                registering, registration(creation, material, UP_UV | ATTESTED, 0, ORIGIN))
            .credential();
    assertThat(credential.publicKey()).isNotEmpty();
    BoundWebAuthnOperations authenticating = operations(List.of(credential));
    var options = assertionOptions(authenticating);
    assertThat(options.getAllowCredentials()).isEmpty();
    assertThat(options.getUserVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
    assertThat(
            verifyAssertion(
                authenticating,
                assertion(options, material, UP_UV, 1, ORIGIN, AUTHENTICATOR_RP, user.getId())))
        .extracting(AuthenticationCredential::id)
        .isEqualTo(material.id().getBytes());
    assertThat(updated.get().counter()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"origin", "rp", "challenge", "account", "uv", "up", "signature"})
  void deniesWrongBindingsOrFlagsAndNeverInvokesThePersistenceSink(String failure)
      throws Exception {
    Material material = material("EC");
    var registering = operations(List.of());
    var credential =
        registrationProof(
                registering,
                registration(creation(registering), material, UP_UV | ATTESTED, 0, ORIGIN))
            .credential();
    var authenticating = operations(List.of(credential));
    var options = assertionOptions(authenticating);
    var supplied = failure.equals("challenge") ? assertionOptions(authenticating) : options;
    Material signing =
        failure.equals("signature")
            ? new Material(
                material("EC").pair(),
                material.publicKey(),
                material.id(),
                material.signatureAlgorithm())
            : material;
    var proof =
        assertion(
            supplied,
            signing,
            failure.equals("uv") ? 0x01 : failure.equals("up") ? 0x04 : UP_UV,
            1,
            failure.equals("origin") ? "https://foreign.invalid" : ORIGIN,
            failure.equals("rp") ? "foreign.invalid" : AUTHENTICATOR_RP,
            failure.equals("account") ? Bytes.random() : user.getId());
    var bound = new RelyingPartyAuthenticationRequest(options, proof.getPublicKey());
    assertThatThrownBy(() -> verifyAssertion(authenticating, bound))
        .isInstanceOf(AuthenticationFailedException.class)
        .hasMessage("Authentication failed.");
    assertThat(updated.get()).isNull();
  }

  @Test
  void checksTheCurrentPersistedCounterRatherThanAnOriginalAttestation() throws Exception {
    Material material = material("EC");
    var registering = operations(List.of());
    var credential =
        registrationProof(
                registering,
                registration(creation(registering), material, UP_UV | ATTESTED, 0, ORIGIN))
            .credential();
    var first = operations(List.of(credential));
    verifyAssertion(
        first,
        assertion(
            assertionOptions(first), material, UP_UV, 5, ORIGIN, AUTHENTICATOR_RP, user.getId()));
    var current = updated.get();
    updated.set(null);
    var second = operations(List.of(current));
    assertThatThrownBy(
            () ->
                verifyAssertion(
                    second,
                    assertion(
                        assertionOptions(second),
                        material,
                        UP_UV,
                        5,
                        ORIGIN,
                        AUTHENTICATOR_RP,
                        user.getId())))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(updated.get()).isNull();
    verifyAssertion(
        second,
        assertion(
            assertionOptions(second), material, UP_UV, 6, ORIGIN, AUTHENTICATOR_RP, user.getId()));
    assertThat(updated.get().counter()).isEqualTo(6);
  }

  @Test
  void acceptsVerifiedSyncedCounterResetAndPreservesTheHighWatermarkButRejectsEligibilityChange()
      throws Exception {
    Material material = material("EC");
    var registering = operations(List.of());
    var credential =
        registrationProof(
                registering,
                registration(
                    creation(registering),
                    material,
                    UP_UV | ATTESTED | BACKUP_ELIGIBLE,
                    10,
                    ORIGIN))
            .credential();
    var synced = operations(List.of(credential));
    verifyAssertion(
        synced,
        assertion(
            assertionOptions(synced),
            material,
            UP_UV | BACKUP_ELIGIBLE | BACKED_UP,
            0,
            ORIGIN,
            AUTHENTICATOR_RP,
            user.getId()));
    assertThat(updated.get().counter()).isEqualTo(10);
    assertThat(updated.get().backedUp()).isTrue();
    updated.set(null);
    assertThatThrownBy(
            () ->
                verifyAssertion(
                    synced,
                    assertion(
                        assertionOptions(synced),
                        material,
                        UP_UV,
                        0,
                        ORIGIN,
                        AUTHENTICATOR_RP,
                        user.getId())))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(updated.get()).isNull();
  }

  @Test
  void rejectsPrivateKeyMaterialUnsafeConfigurationAndInvalidServerChallenges() throws Exception {
    Material material = material("EC");
    Material privateMaterial =
        new Material(
            material.pair(),
            EC2COSEKey.create(material.pair(), COSEAlgorithmIdentifier.ES256),
            material.id(),
            material.signatureAlgorithm());
    var registering = operations(List.of());
    assertThatThrownBy(
            () ->
                registrationProof(
                        registering,
                        registration(
                            creation(registering), privateMaterial, UP_UV | ATTESTED, 0, ORIGIN))
                    .credential())
        .isInstanceOf(AuthenticationFailedException.class);
    for (String origin :
        new String[] {
          "http://localhost",
          "https://127.0.0.1",
          "https://localhost/path",
          "https://owner@localhost",
          "https://localhost:0",
          "https://localhost?query",
          "https://localhost."
        }) {
      assertThatThrownBy(
              () -> new BoundWebAuthnOperations(origin, user, List.of(), new CryptoWorkLimiter()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int length : new int[] {31, 33}) {
      assertThatThrownBy(() -> registering.creationOptions(new byte[length]))
          .isInstanceOf(AuthenticationFailedException.class);
      assertThatThrownBy(() -> registering.assertionOptions(new byte[length]))
          .isInstanceOf(AuthenticationFailedException.class);
    }
  }

  @Test
  void rebuildsPublicKeysWithoutAuthenticatorSuppliedOptionalMetadata() throws Exception {
    Material material = material("EC");
    EC2COSEKey original = (EC2COSEKey) material.publicKey();
    COSEKey decorated =
        new EC2COSEKey(
            new byte[] {1, 2, 3},
            original.getAlgorithm(),
            List.of(COSEKeyOperation.VERIFY),
            original.getCurve(),
            original.getX(),
            original.getY());
    var registering = operations(List.of());
    AuthenticationCredential stored =
        registrationProof(
                registering,
                registration(
                    creation(registering),
                    new Material(
                        material.pair(), decorated, material.id(), material.signatureAlgorithm()),
                    UP_UV | ATTESTED,
                    0,
                    ORIGIN))
            .credential();
    COSEKey minimized =
        new ObjectConverter().getCborMapper().readValue(stored.publicKey(), COSEKey.class);
    assertThat(minimized.getKeyId()).isNull();
    assertThat(minimized.getKeyOps()).isNull();
    assertThat(minimized.getBaseIV()).isNull();
    assertThat(minimized.getPublicKey()).isEqualTo(original.getPublicKey());
    var authenticating = operations(List.of(stored));
    verifyAssertion(
        authenticating,
        assertion(
            assertionOptions(authenticating),
            material,
            UP_UV,
            1,
            ORIGIN,
            AUTHENTICATOR_RP,
            user.getId()));
    assertThat(updated.get()).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {WEAK_RSA, "unsupported-curve"})
  void rejectsKeysOutsideTheExplicitPublicKeyProfile(String failure) throws Exception {
    KeyPairGenerator generator =
        KeyPairGenerator.getInstance(failure.equals(WEAK_RSA) ? RSA_ALGORITHM : "EC");
    if (failure.equals(WEAK_RSA)) {
      generator.initialize(1024);
    } else {
      generator.initialize(new ECGenParameterSpec("secp384r1"));
    }
    KeyPair pair = generator.generateKeyPair();
    COSEKey key =
        failure.equals(WEAK_RSA)
            ? RSACOSEKey.create((RSAPublicKey) pair.getPublic(), COSEAlgorithmIdentifier.RS256)
            : EC2COSEKey.create((ECPublicKey) pair.getPublic(), COSEAlgorithmIdentifier.ES256);
    Material material = new Material(pair, key, Bytes.random(), "unused-registration-signature");
    var registering = operations(List.of());
    assertThatThrownBy(
            () ->
                registrationProof(
                        registering,
                        registration(creation(registering), material, UP_UV | ATTESTED, 0, ORIGIN))
                    .credential())
        .isInstanceOf(AuthenticationFailedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cross-origin", "duplicate-field", "wrong-type"})
  void rejectsSignedMalformedOrCrossOriginClientData(String failure) throws Exception {
    Material material = material("EC");
    var registering = operations(List.of());
    var stored =
        registrationProof(
                registering,
                registration(creation(registering), material, UP_UV | ATTESTED, 0, ORIGIN))
            .credential();
    var authenticating = operations(List.of(stored));
    var options = assertionOptions(authenticating);
    String valid =
        new String(
            clientData("webauthn.get", options.getChallenge(), ORIGIN), StandardCharsets.UTF_8);
    String malformed =
        switch (failure) {
          case "cross-origin" -> valid.replace("false", "true");
          case "duplicate-field" -> valid.replace("{", "{\"origin\":\"https://foreign.invalid\",");
          default -> valid.replace("webauthn.get", "webauthn.create");
        };
    var proof =
        signedAssertion(
            options,
            material,
            UP_UV,
            1,
            AUTHENTICATOR_RP,
            user.getId(),
            malformed.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> verifyAssertion(authenticating, proof))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(updated.get()).isNull();
  }

  @Test
  void configuredDefaultHttpsPortMatchesTheBrowsersCanonicalOrigin() throws Exception {
    Material material = material("EC");
    var registering =
        new BoundWebAuthnOperations(
            "HTTPS://LOCALHOST:443", user, List.of(), new CryptoWorkLimiter());
    var credential =
        registrationProof(
                registering,
                registration(
                    creation(registering), material, UP_UV | ATTESTED, 0, "https://localhost"))
            .credential();
    var authenticating =
        new BoundWebAuthnOperations(
            "https://localhost", user, List.of(credential), new CryptoWorkLimiter());
    verifyAssertion(
        authenticating,
        assertion(
            assertionOptions(authenticating),
            material,
            UP_UV,
            1,
            "https://localhost",
            AUTHENTICATOR_RP,
            user.getId()));
    assertThat(updated.get()).isNotNull();
    updated.set(null);
    assertThatThrownBy(
            () ->
                verifyAssertion(
                    authenticating,
                    assertion(
                        assertionOptions(authenticating),
                        material,
                        UP_UV,
                        2,
                        ORIGIN,
                        AUTHENTICATOR_RP,
                        user.getId())))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(updated.get()).isNull();
  }

  @Test
  void verificationRejectsMissingOrInvalidConsumedNoncesBeforeReadingProofs() {
    var bound = operations(List.of());
    for (byte[] nonce : new byte[][] {null, new byte[0], new byte[31], new byte[33]}) {
      assertThatThrownBy(() -> bound.verifyRegistration(nonce, null))
          .isInstanceOf(AuthenticationFailedException.class);
      assertThatThrownBy(() -> bound.verifyAssertion(nonce, null))
          .isInstanceOf(AuthenticationFailedException.class);
    }
  }

  private static BoundWebAuthnOperations.VerifiedCredential registrationProof(
      BoundWebAuthnOperations operations, RelyingPartyRegistrationRequest request) {
    return operations.verifyRegistration(
        request.getCreationOptions().getChallenge().getBytes(), request.getPublicKey());
  }

  private static BoundWebAuthnOperations.VerifiedCredential assertionProof(
      BoundWebAuthnOperations operations, RelyingPartyAuthenticationRequest request) {
    return operations.verifyAssertion(
        request.getRequestOptions().getChallenge().getBytes(), request.getPublicKey());
  }

  private AuthenticationCredential verifyAssertion(
      BoundWebAuthnOperations operations, RelyingPartyAuthenticationRequest request) {
    AuthenticationCredential verified = assertionProof(operations, request).credential();
    updated.set(verified);
    return verified;
  }

  private BoundWebAuthnOperations operations(List<AuthenticationCredential> credentials) {
    return new BoundWebAuthnOperations(ORIGIN, user, credentials, new CryptoWorkLimiter());
  }

  private static PublicKeyCredentialCreationOptions creation(BoundWebAuthnOperations operations) {
    return operations.creationOptions(null);
  }

  private static PublicKeyCredentialRequestOptions assertionOptions(
      BoundWebAuthnOperations operations) {
    return operations.assertionOptions(null);
  }

  private static RelyingPartyRegistrationRequest registration(
      PublicKeyCredentialCreationOptions options,
      Material material,
      int flags,
      long counter,
      String origin)
      throws Exception {
    byte[] cose = new ObjectConverter().getCborMapper().writeValueAsBytes(material.publicKey());
    byte[] id = material.id().getBytes();
    byte[] auth =
        ByteBuffer.allocate(37 + 16 + 2 + id.length + cose.length)
            .put(digest(AUTHENTICATOR_RP.getBytes(StandardCharsets.US_ASCII)))
            .put((byte) flags)
            .putInt((int) counter)
            .put(new byte[16])
            .putShort((short) id.length)
            .put(id)
            .put(cose)
            .array();
    byte[] attestation =
        new ObjectConverter()
            .getCborMapper()
            .writeValueAsBytes(Map.of("fmt", "none", "authData", auth, "attStmt", Map.of()));
    byte[] client = clientData("webauthn.create", options.getChallenge(), origin);
    var response =
        AuthenticatorAttestationResponse.builder()
            .attestationObject(new Bytes(attestation))
            .clientDataJSON(new Bytes(client))
            .build();
    PublicKeyCredential<AuthenticatorAttestationResponse> credential =
        PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
            .id(material.id().toBase64UrlString())
            .rawId(material.id())
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(response)
            .build();
    return new RelyingPartyRegistrationRequest() {
      @Override
      public PublicKeyCredentialCreationOptions getCreationOptions() {
        return options;
      }

      @Override
      public RelyingPartyPublicKey getPublicKey() {
        return new RelyingPartyPublicKey(credential, "Synthetic passkey");
      }
    };
  }

  private static RelyingPartyAuthenticationRequest assertion(
      PublicKeyCredentialRequestOptions options,
      Material material,
      int flags,
      long counter,
      String origin,
      String rp,
      Bytes userHandle)
      throws Exception {
    return signedAssertion(
        options,
        material,
        flags,
        counter,
        rp,
        userHandle,
        clientData("webauthn.get", options.getChallenge(), origin));
  }

  private static RelyingPartyAuthenticationRequest signedAssertion(
      PublicKeyCredentialRequestOptions options,
      Material material,
      int flags,
      long counter,
      String rp,
      Bytes userHandle,
      byte[] client)
      throws Exception {
    byte[] auth =
        ByteBuffer.allocate(37)
            .put(digest(rp.getBytes(StandardCharsets.US_ASCII)))
            .put((byte) flags)
            .putInt((int) counter)
            .array();
    Signature signature = Signature.getInstance(material.signatureAlgorithm());
    signature.initSign(material.pair().getPrivate());
    signature.update(auth);
    signature.update(digest(client));
    var response =
        AuthenticatorAssertionResponse.builder()
            .authenticatorData(new Bytes(auth))
            .clientDataJSON(new Bytes(client))
            .signature(new Bytes(signature.sign()))
            .userHandle(userHandle)
            .build();
    PublicKeyCredential<AuthenticatorAssertionResponse> credential =
        PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
            .id(material.id().toBase64UrlString())
            .rawId(material.id())
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(response)
            .build();
    return new RelyingPartyAuthenticationRequest(options, credential);
  }

  private static byte[] clientData(String type, Bytes challenge, String origin) {
    return JsonMapper.builder()
        .build()
        .writeValueAsBytes(
            Map.of(
                "type",
                type,
                "challenge",
                challenge.toBase64UrlString(),
                "origin",
                origin,
                "crossOrigin",
                false));
  }

  private static byte[] digest(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  private static Material material(String algorithm) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    if (algorithm.equals("EC")) {
      generator.initialize(new ECGenParameterSpec("secp256r1"));
    } else if (algorithm.equals(RSA_ALGORITHM)) {
      generator.initialize(2048);
    }
    KeyPair pair = generator.generateKeyPair();
    COSEKey publicKey =
        switch (algorithm) {
          case "EC" ->
              EC2COSEKey.create((ECPublicKey) pair.getPublic(), COSEAlgorithmIdentifier.ES256);
          case RSA_ALGORITHM ->
              RSACOSEKey.create((RSAPublicKey) pair.getPublic(), COSEAlgorithmIdentifier.RS256);
          case ED_ALGORITHM ->
              EdDSACOSEKey.create((EdECPublicKey) pair.getPublic(), COSEAlgorithmIdentifier.EdDSA);
          default -> throw new IllegalArgumentException("Unsupported synthetic algorithm.");
        };
    String signatureAlgorithm =
        switch (algorithm) {
          case "EC" -> "SHA256withECDSA";
          case RSA_ALGORITHM -> "SHA256withRSA";
          default -> ED_ALGORITHM;
        };
    return new Material(pair, publicKey, Bytes.random(), signatureAlgorithm);
  }

  private record Material(KeyPair pair, COSEKey publicKey, Bytes id, String signatureAlgorithm) {
    @Override
    public String toString() {
      return "Material[redacted]";
    }
  }
}
