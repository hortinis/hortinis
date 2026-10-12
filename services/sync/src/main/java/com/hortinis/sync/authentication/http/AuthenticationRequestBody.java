package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.PasswordPolicy;
import com.hortinis.sync.protocol.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed, bounded authentication JSON; parsing alone cannot consume proof or grant authority. */
public final class AuthenticationRequestBody {
  private static final int MAX_DOCUMENT = 65_536;
  private static final int MAX_SCALAR_DOCUMENT = 16_384;
  private static final int HANDLE_BYTES = 32;
  private static final String RESPONSE = "response";
  private static final String CLIENT_DATA = "clientDataJSON";
  private static final String LABEL = "label";
  private static final JsonMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_DOCUMENT)
                          .maxStringLength(43_691)
                          .maxNestingDepth(4)
                          .maxTokenCount(64)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final JsonNode body;

  private AuthenticationRequestBody(JsonNode body) {
    this.body = body;
  }

  /** Must be called only after request guards and any required one-use proof consumption. */
  public static AuthenticationRequestBody read(InputStream stream) {
    return read(stream, true);
  }

  /** Scalar credential envelopes have a tighter bound than WebAuthn binary envelopes. */
  public static AuthenticationRequestBody read(InputStream stream, boolean webAuthnProof) {
    int maximum = webAuthnProof ? MAX_DOCUMENT : MAX_SCALAR_DOCUMENT;
    try {
      byte[] bytes = stream.readNBytes(maximum + 1);
      if (bytes.length > maximum) {
        throw invalid();
      }
      JsonNode body = MAPPER.readTree(bytes);
      requireFields(body, Set.of(), null);
      return new AuthenticationRequestBody(body);
    } catch (JacksonException | IOException exception) {
      throw invalid();
    }
  }

  /** Exact contract properties, including separately specified optional properties. */
  public AuthenticationRequestBody fields(Set<String> required, Set<String> optional) {
    requireFields(body, required, optional);
    return this;
  }

  /** Reads exact scalar text without coercion or normalization. */
  public String text(String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isString()) {
      throw invalid();
    }
    return value.stringValue();
  }

  /** Canonical login validation uses the selected domain policy. */
  public String login() {
    try {
      return PasswordPolicy.canonicalLogin(text("login"));
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  /** Password wire bounds do not replace policy or password verification in the service. */
  public String password(String name, boolean newPassword) {
    String value = text(name);
    if (!scalarText(value, 128)
        || value.codePointCount(0, value.length()) < (newPassword ? 15 : 1)
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
      throw invalid();
    }
    return value;
  }

  /** Semantic ticket/code/target validation uses the containing operation, never this parser. */
  public String random(String name) {
    String value = text(name);
    if (!AuthenticationSecrets.isRandomValue(value)) {
      throw invalid();
    }
    return value;
  }

  /** Owned credential selection is independently checked by the service. */
  public byte[] credentialTarget() {
    return decoded(body, "credentialId", 1536);
  }

  /** Registration construction discards unsupported attestation/transport/extension metadata. */
  public RelyingPartyPublicKey registration() {
    fields(Set.of("id", "rawId", "type", RESPONSE), Set.of(LABEL));
    Bytes id = credentialId();
    JsonNode response = body.get(RESPONSE);
    requireFields(response, Set.of(CLIENT_DATA, "attestationObject"), Set.of());
    String label = "";
    if (body.has(LABEL)) {
      label = text(LABEL);
      if (!scalarText(label, 64) || label.indexOf('\0') >= 0) {
        throw invalid();
      }
    }
    var proof =
        AuthenticatorAttestationResponse.builder()
            .clientDataJSON(new Bytes(decoded(response, CLIENT_DATA, 3072)))
            .attestationObject(new Bytes(decoded(response, "attestationObject", 32768)))
            .build();
    var credential =
        PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
            .id(id.toBase64UrlString())
            .rawId(id)
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(proof)
            .build();
    return new RelyingPartyPublicKey(credential, label);
  }

  /** Discoverable assertion requires the exact 32-byte user handle selected by the contract. */
  public PublicKeyCredential<AuthenticatorAssertionResponse> assertion() {
    fields(Set.of("id", "rawId", "type", RESPONSE), Set.of());
    Bytes id = credentialId();
    JsonNode response = body.get(RESPONSE);
    requireFields(
        response, Set.of(CLIENT_DATA, "authenticatorData", "signature", "userHandle"), Set.of());
    byte[] handle = decoded(response, "userHandle", HANDLE_BYTES);
    if (handle.length != HANDLE_BYTES) {
      throw invalid();
    }
    var proof =
        AuthenticatorAssertionResponse.builder()
            .clientDataJSON(new Bytes(decoded(response, CLIENT_DATA, 3072)))
            .authenticatorData(new Bytes(decoded(response, "authenticatorData", 768)))
            .signature(new Bytes(decoded(response, "signature", 768)))
            .userHandle(new Bytes(handle))
            .build();
    return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
        .id(id.toBase64UrlString())
        .rawId(id)
        .type(PublicKeyCredentialType.PUBLIC_KEY)
        .response(proof)
        .build();
  }

  @Override
  public String toString() {
    return "AuthenticationRequestBody[redacted]";
  }

  private Bytes credentialId() {
    byte[] id = decoded(body, "id", 1536);
    if (!text("id").equals(text("rawId")) || !"public-key".equals(text("type"))) {
      throw invalid();
    }
    return new Bytes(id);
  }

  private static byte[] decoded(JsonNode object, String name, int maximumBytes) {
    JsonNode value = object.get(name);
    if (value == null || !value.isString()) {
      throw invalid();
    }
    String encoded = value.stringValue();
    if (encoded.isEmpty()
        || encoded.length() > (maximumBytes * 4 + 2) / 3
        || !encoded.matches("[A-Za-z0-9_-]+")) {
      throw invalid();
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (bytes.length == 0
          || bytes.length > maximumBytes
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
        throw invalid();
      }
      return bytes;
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static void requireFields(JsonNode object, Set<String> required, Set<String> optional) {
    if (object == null || !object.isObject()) {
      throw invalid();
    }
    if (optional == null) {
      return;
    }
    Set<String> actual = new HashSet<>();
    object.properties().forEach(entry -> actual.add(entry.getKey()));
    Set<String> allowed = new HashSet<>(required);
    allowed.addAll(optional);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
      throw invalid();
    }
  }

  private static boolean scalarText(String value, int maximum) {
    if (value.codePointCount(0, value.length()) > maximum) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        index++;
        if (index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          return false;
        }
      } else if (Character.isLowSurrogate(character)) {
        return false;
      }
    }
    return true;
  }

  private static InvalidRequestException invalid() {
    return new InvalidRequestException();
  }
}
