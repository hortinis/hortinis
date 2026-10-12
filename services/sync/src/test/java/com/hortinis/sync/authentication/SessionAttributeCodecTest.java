package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionAttributeCodecTest {
  @Test
  void roundTripsOnlyExplicitCsrfAndNonAuthorizingReferenceWithoutJavaSerialization() {
    var conversion = SessionAttributeCodec.conversionService();
    CsrfToken csrf =
        new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", AuthenticationSecrets.randomValue());
    byte[] encoded = conversion.convert(csrf, byte[].class);
    CsrfToken decoded = (CsrfToken) conversion.convert(encoded, Object.class);
    assertThat(decoded.getHeaderName()).isEqualTo(csrf.getHeaderName());
    assertThat(decoded.getParameterName()).isEqualTo(csrf.getParameterName());
    assertThat(decoded.getToken()).isEqualTo(csrf.getToken());
    SessionAuthorityReference reference = new SessionAuthorityReference(UUID.randomUUID());
    assertThat(conversion.convert(conversion.convert(reference, byte[].class), Object.class))
        .isEqualTo(reference);
    assertThat(reference.toString()).doesNotContain(reference.value().toString());
    assertThat(new String(encoded, StandardCharsets.US_ASCII)).startsWith("v1:csrf:");
  }

  @Test
  void rejectsPrincipalGraphsStringsWrongFieldsMalformedOrOversizedStoredData() {
    for (Object rejected :
        new Object[] {
          Map.of("password", "synthetic"),
          "synthetic principal",
          new DefaultCsrfToken("foreign-header", "_csrf", "synthetic"),
          new DefaultCsrfToken("X-CSRF-TOKEN", "foreign-parameter", "synthetic")
        }) {
      assertThatThrownBy(() -> SessionAttributeCodec.encode(rejected))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported authentication session attribute.");
    }
    for (byte[] rejected :
        new byte[][] {
          new byte[] {(byte) 0xac, (byte) 0xed, 0, 5},
          "v1:authority:1-1-1-1-1".getBytes(StandardCharsets.US_ASCII),
          "v1:csrf:unexpected whitespace".getBytes(StandardCharsets.US_ASCII),
          new byte[1025],
          new byte[] {'v', '1', ':', 'c', 's', 'r', 'f', ':', (byte) 0xff}
        }) {
      assertThatThrownBy(() -> SessionAttributeCodec.decode(rejected))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported authentication session attribute.");
    }
  }
}
