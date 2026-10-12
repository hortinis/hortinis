package com.hortinis.sync.authentication.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.protocol.InvalidRequestException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

class AuthenticationRequestBodyTest {
  private static final String LOGIN = "login";
  private static final String LABEL = "label";
  private static final String TICKET = "ticket";
  private static final String RAW_ID = "rawId";
  private static final String PASSWORD = "password";
  private static final String CLIENT_DATA = "clientDataJSON";
  private static final String ATTESTATION = "attestationObject";
  private static final String AUTHENTICATOR = "authenticatorData";
  private static final String SIGNATURE = "signature";
  private static final String HANDLE = "userHandle";
  private static final String RESPONSE = "response";
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

  @Test
  void preservesExactPasswordAndCanonicalizesOnlyLoginWithoutDiagnosticContent() {
    String password = "  Synthetic é 🔑 phrase  ";
    var body = read(Map.of(LOGIN, "Synthetic.Gardener", PASSWORD, password));
    body.fields(Set.of(LOGIN, PASSWORD), Set.of());
    assertThat(body.login()).isEqualTo("synthetic.gardener");
    assertThat(body.password(PASSWORD, true)).isEqualTo(password);
    assertThat(body.toString()).isEqualTo("AuthenticationRequestBody[redacted]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "[]",
        "true",
        "{} {}",
        "{\"x\":1,\"x\":2}",
        "{\"x\":{\"y\":1,\"y\":2}}",
        "{\"x\": [[[[[]]]]]}",
        "{"
      })
  void rejectsMalformedNonObjectDuplicateTrailingAndDeepJsonWithFixedError(String input) {
    assertThatThrownBy(() -> readJson(input))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("The request is invalid.")
        .hasCause(null);
  }

  @Test
  void capsActualBytesEvenWithoutContentLength() {
    assertThatThrownBy(() -> readJson(" ".repeat(65_537)))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> readJson("{\"x\":\"" + "a".repeat(43_692) + "\"}"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> readJson("{\"x\":[" + "0,".repeat(65) + "0]}"))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"password\":null}",
        "{\"password\":1}",
        "{\"password\":true}",
        "{\"password\":[]}"
      })
  void neverCoercesScalarFields(String json) {
    assertThatThrownBy(() -> readJson(json).text(PASSWORD))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void deniesMissingUnknownAndOptionalNullProperties() {
    assertThatThrownBy(
            () -> read(Map.of(LOGIN, "synthetic")).fields(Set.of(LOGIN, PASSWORD), Set.of()))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                read(Map.of(LOGIN, "synthetic", "extra", "private"))
                    .fields(Set.of(LOGIN), Set.of()))
        .isInstanceOf(InvalidRequestException.class);
    Map<String, Object> registration = registration();
    registration.put(LABEL, null);
    assertThatThrownBy(() -> read(registration).registration())
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void validatesPasswordScalarBoundsAndRejectsUnpairedSurrogates() {
    assertThat(read(Map.of(PASSWORD, "🔑".repeat(128))).password(PASSWORD, false))
        .isEqualTo("🔑".repeat(128));
    for (String value :
        new String[] {
          "",
          "a".repeat(129),
          new String(new char[] {Character.MIN_HIGH_SURROGATE}),
          new String(new char[] {Character.MIN_LOW_SURROGATE})
        }) {
      assertThatThrownBy(() -> read(Map.of(PASSWORD, value)).password(PASSWORD, false))
          .isInstanceOf(InvalidRequestException.class);
    }
    assertThatThrownBy(() -> read(Map.of(PASSWORD, "x".repeat(14))).password(PASSWORD, true))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void exactRandomValuesAndOwnedCredentialSelectionHaveSeparateShapes() {
    String value = encoded(32);
    assertThat(read(Map.of(TICKET, value)).random(TICKET)).isEqualTo(value);
    assertThat(read(Map.of("credentialId", encoded(1536))).credentialTarget()).hasSize(1536);
    assertThatThrownBy(() -> read(Map.of(TICKET, encoded(31))).random(TICKET))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> read(Map.of("credentialId", encoded(1537))).credentialTarget())
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void constructsEstablishedProofTypesAtEverySelectedDecodedBound() {
    Map<String, Object> registration = registration();
    registration.put("id", encoded(1536));
    registration.put(RAW_ID, encoded(1536));
    registration.put(RESPONSE, Map.of(CLIENT_DATA, encoded(3072), ATTESTATION, encoded(32768)));
    var registered = read(registration).registration().getCredential();
    assertThat(registered.getRawId().getBytes()).hasSize(1536);
    assertThat(registered.getResponse().getAttestationObject().getBytes()).hasSize(32768);
    Map<String, Object> assertion = assertion();
    assertion.put(
        RESPONSE,
        Map.of(
            CLIENT_DATA,
            encoded(3072),
            AUTHENTICATOR,
            encoded(768),
            SIGNATURE,
            encoded(768),
            HANDLE,
            encoded(32)));
    var authenticated = read(assertion).assertion();
    assertThat(authenticated.getResponse().getClientDataJSON().getBytes()).hasSize(3072);
    assertThat(authenticated.getResponse().getSignature().getBytes()).hasSize(768);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "AA=", "AA==", "AB", "A", "+A", "/A", "é", "AA\n"})
  void rejectsNonCanonicalBase64WithoutCredentialDiagnostics(String id) {
    Map<String, Object> registration = registration();
    registration.put("id", id);
    registration.put(RAW_ID, id);
    assertThatThrownBy(() -> read(registration).registration())
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("The request is invalid.")
        .hasCause(null);
  }

  @ParameterizedTest
  @MethodSource("oversizedProofFields")
  void rejectsEachOversizedDecodedProofField(boolean registration, String field, int length) {
    Map<String, Object> body = registration ? registration() : assertion();
    @SuppressWarnings("unchecked")
    Map<String, Object> response = new LinkedHashMap<>((Map<String, Object>) body.get(RESPONSE));
    response.put(field, encoded(length));
    body.put(RESPONSE, response);
    assertThatThrownBy(
            () -> {
              if (registration) {
                read(body).registration();
              } else {
                read(body).assertion();
              }
            })
        .isInstanceOf(InvalidRequestException.class);
  }

  static Stream<Arguments> oversizedProofFields() {
    return Stream.of(
        Arguments.of(true, CLIENT_DATA, 3073),
        Arguments.of(true, ATTESTATION, 32769),
        Arguments.of(false, CLIENT_DATA, 3073),
        Arguments.of(false, AUTHENTICATOR, 769),
        Arguments.of(false, SIGNATURE, 769),
        Arguments.of(false, HANDLE, 33),
        Arguments.of(false, HANDLE, 31));
  }

  @Test
  void deniesIdMismatchWrongTypeUnselectedMetadataAndInvalidLabel() {
    for (Map<String, Object> replacement :
        java.util.List.<Map<String, Object>>of(
            Map.of(RAW_ID, "AQ"),
            Map.of("type", "other"),
            Map.of("transports", java.util.List.of("internal")),
            Map.of(LABEL, "a".repeat(65)),
            Map.of(LABEL, "\u0000"),
            Map.of(LABEL, new String(new char[] {Character.MIN_HIGH_SURROGATE})))) {
      Map<String, Object> body = registration();
      body.putAll(replacement);
      assertThatThrownBy(() -> read(body).registration())
          .isInstanceOf(InvalidRequestException.class);
    }
    Map<String, Object> body = assertion();
    body.put(
        RESPONSE,
        Map.of(
            CLIENT_DATA,
            "AA",
            AUTHENTICATOR,
            "AA",
            SIGNATURE,
            "AA",
            HANDLE,
            encoded(32),
            "extra",
            "private"));
    assertThatThrownBy(() -> read(body).assertion()).isInstanceOf(InvalidRequestException.class);
  }

  private static Map<String, Object> registration() {
    Map<String, Object> body = credential();
    body.put(RESPONSE, Map.of(CLIENT_DATA, "AA", ATTESTATION, "AA"));
    return body;
  }

  private static Map<String, Object> assertion() {
    Map<String, Object> body = credential();
    body.put(
        RESPONSE,
        Map.of(CLIENT_DATA, "AA", AUTHENTICATOR, "AA", SIGNATURE, "AA", HANDLE, encoded(32)));
    return body;
  }

  private static Map<String, Object> credential() {
    return new LinkedHashMap<>(Map.of("id", "AA", RAW_ID, "AA", "type", "public-key"));
  }

  private static String encoded(int bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[bytes]);
  }

  private static AuthenticationRequestBody read(Map<String, Object> body) {
    return readJson(MAPPER.writeValueAsString(body));
  }

  private static AuthenticationRequestBody readJson(String input) {
    return AuthenticationRequestBody.read(
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
  }
}
