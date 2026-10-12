package com.hortinis.sync;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import tools.jackson.databind.json.JsonMapper;

/** Real ephemeral signatures for service tests; private keys exist only in the test process. */
final class SyntheticAuthenticationPasskey {
  static final String ORIGIN = "https://localhost:4200";
  private final KeyPair keys;
  private final Bytes credentialId = Bytes.random();
  private final boolean backupEligible;

  SyntheticAuthenticationPasskey(boolean backupEligible) throws Exception {
    this.backupEligible = backupEligible;
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    keys = generator.generateKeyPair();
  }

  RelyingPartyPublicKey registration(PublicKeyCredentialCreationOptions options, boolean uv)
      throws Exception {
    byte[] cose =
        new ObjectConverter()
            .getCborMapper()
            .writeValueAsBytes(
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
    byte[] id = credentialId.getBytes();
    byte[] auth =
        ByteBuffer.allocate(37 + 16 + 2 + id.length + cose.length)
            .put(digest("localhost".getBytes(StandardCharsets.US_ASCII)))
            .put((byte) (0x41 | (uv ? 0x04 : 0) | (backupEligible ? 0x08 : 0)))
            .putInt(0)
            .put(new byte[16])
            .putShort((short) id.length)
            .put(id)
            .put(cose)
            .array();
    byte[] attestation =
        new ObjectConverter()
            .getCborMapper()
            .writeValueAsBytes(Map.of("fmt", "none", "authData", auth, "attStmt", Map.of()));
    var response =
        AuthenticatorAttestationResponse.builder()
            .attestationObject(new Bytes(attestation))
            .clientDataJSON(new Bytes(client("webauthn.create", options.getChallenge())))
            .build();
    var credential =
        PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
            .id(credentialId.toBase64UrlString())
            .rawId(credentialId)
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(response)
            .build();
    return new RelyingPartyPublicKey(credential, "Synthetic passkey");
  }

  PublicKeyCredential<AuthenticatorAssertionResponse> assertion(
      PublicKeyCredentialRequestOptions options, byte[] userHandle, long counter) throws Exception {
    byte[] client = client("webauthn.get", options.getChallenge());
    byte[] auth =
        ByteBuffer.allocate(37)
            .put(digest("localhost".getBytes(StandardCharsets.US_ASCII)))
            .put((byte) (0x05 | (backupEligible ? 0x08 : 0)))
            .putInt((int) counter)
            .array();
    var signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign(keys.getPrivate());
    signature.update(auth);
    signature.update(digest(client));
    var response =
        AuthenticatorAssertionResponse.builder()
            .authenticatorData(new Bytes(auth))
            .clientDataJSON(new Bytes(client))
            .signature(new Bytes(signature.sign()))
            .userHandle(new Bytes(userHandle))
            .build();
    return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
        .id(credentialId.toBase64UrlString())
        .rawId(credentialId)
        .type(PublicKeyCredentialType.PUBLIC_KEY)
        .response(response)
        .build();
  }

  byte[] id() {
    return credentialId.getBytes().clone();
  }

  private static byte[] client(String type, Bytes challenge) {
    return JsonMapper.builder()
        .build()
        .writeValueAsBytes(
            Map.of(
                "type",
                type,
                "challenge",
                challenge.toBase64UrlString(),
                "origin",
                ORIGIN,
                "crossOrigin",
                false));
  }

  private static byte[] digest(byte[] material) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(material);
  }

  @Override
  public String toString() {
    return "SyntheticAuthenticationPasskey[redacted]";
  }
}
