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
    String target = AuthenticationSecrets.randomValue();
    assertThat(conversion.convert(conversion.convert(target, byte[].class), Object.class))
        .isEqualTo(target);
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
          "v1:target:unexpected".getBytes(StandardCharsets.US_ASCII),
          new byte[1025],
          new byte[] {'v', '1', ':', 'c', 's', 'r', 'f', ':', (byte) 0xff}
        }) {
      assertThatThrownBy(() -> SessionAttributeCodec.decode(rejected))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported authentication session attribute.");
    }
  }

  @Test
  void nativeSecurityContextUsesSpringJsonModulesAndRetainsNoCredentials() {
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    UUID account = UUID.randomUUID();
    context.setAuthentication(
        AuthenticationIdentity.completed(account, 7, java.time.Instant.now()));
    byte[] encoded = SessionAttributeCodec.encode(context);
    var decoded =
        (org.springframework.security.core.context.SecurityContext)
            SessionAttributeCodec.decode(encoded);
    assertThat(AuthenticationIdentity.from(decoded.getAuthentication()))
        .isEqualTo(new AuthenticationIdentity(account, 7));
    assertThat(decoded.getAuthentication().getCredentials()).isNull();
    assertThat(
            ((org.springframework.security.core.userdetails.User)
                    decoded.getAuthentication().getPrincipal())
                .getPassword())
        .isNull();
    assertThat(new String(encoded, StandardCharsets.US_ASCII)).startsWith("v1:security:{");
    assertThatThrownBy(
            () ->
                SessionAttributeCodec.decode(
                    "v1:security:{\"@class\":\"java.lang.ProcessBuilder\"}"
                        .getBytes(StandardCharsets.US_ASCII)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pendingCeremonyUsesBoundedTypedJsonAndSeparatePrincipalIndex() {
    var now = java.time.Instant.now();
    UUID account = UUID.randomUUID();
    var ceremony =
        new AuthenticationSession(
            UUID.randomUUID(),
            account,
            3,
            "recovery",
            now,
            now.plusSeconds(300),
            null,
            now,
            null,
            null,
            null,
            AuthenticationSecrets.randomValue(),
            "passkey",
            new byte[1536]);
    byte[] encoded = SessionAttributeCodec.encode(ceremony);
    var decoded = (AuthenticationSession) SessionAttributeCodec.decode(encoded);
    assertThat(decoded.reference()).isEqualTo(ceremony.reference());
    assertThat(decoded.createdAt()).isEqualTo(now);
    assertThat(decoded.replacementCredential()).hasSize(1536);
    assertThat(SessionAttributeCodec.decode(SessionAttributeCodec.encode(account.toString())))
        .isEqualTo(account.toString());
    assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain("@class");
    for (String invalid :
        new String[] {
          "v1:ceremony:null",
          "v1:ceremony:{}",
          new String(encoded, StandardCharsets.UTF_8).replace("recovery", "full"),
          "v1:account:1-1-1-1-1",
          "v1:account:invalid"
        }) {
      assertThatThrownBy(
              () -> SessionAttributeCodec.decode(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported authentication session attribute.");
    }
  }

  @Test
  void nativePasswordContextContainsOnlyPasswordProofAndNoCredentials() {
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    UUID account = UUID.randomUUID();
    var now = java.time.Instant.now();
    context.setAuthentication(AuthenticationIdentity.password(account, 2, now));
    var decoded =
        (org.springframework.security.core.context.SecurityContext)
            SessionAttributeCodec.decode(SessionAttributeCodec.encode(context));
    assertThat(AuthenticationIdentity.from(decoded.getAuthentication()))
        .isEqualTo(new AuthenticationIdentity(account, 2));
    assertThat(decoded.getAuthentication().getAuthorities())
        .extracting("authority")
        .contains("FACTOR_PASSWORD")
        .doesNotContain("FACTOR_WEBAUTHN");
    assertThat(decoded.getAuthentication().getCredentials()).isNull();
  }
}
