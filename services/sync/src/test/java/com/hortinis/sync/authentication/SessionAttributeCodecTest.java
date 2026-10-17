package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionAttributeCodecTest {
  @Test
  void nativeCredentialFreeContextAndCsrfRoundTripWithoutCeremonies() {
    UUID account = UUID.randomUUID();
    var user =
        User.withUsername(account.toString())
            .password("")
            .authorities(AuthenticationIdentity.versionAuthority(7))
            .build();
    ((org.springframework.security.core.CredentialsContainer) user).eraseCredentials();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    byte[] encoded = SessionAttributeCodec.encode(context);
    var decoded = (SecurityContext) SessionAttributeCodec.decode(encoded);
    assertThat(AuthenticationIdentity.from(decoded.getAuthentication()))
        .isEqualTo(new AuthenticationIdentity(account, 7));
    assertThat(new String(encoded, StandardCharsets.UTF_8))
        .doesNotContain("@example.test", "verifier");
    assertThat(SessionAttributeCodec.decode(SessionAttributeCodec.encode(account.toString())))
        .isEqualTo(account.toString());
    var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
    assertThat(
            ((DefaultCsrfToken) SessionAttributeCodec.decode(SessionAttributeCodec.encode(csrf)))
                .getToken())
        .isEqualTo(csrf.getToken());
  }

  @Test
  void rejectsCredentialsRequestDetailsLegacyAttributesAndJavaSerialization() {
    var user =
        User.withUsername(UUID.randomUUID().toString())
            .password("synthetic-verifier")
            .authorities(AuthenticationIdentity.versionAuthority(1))
            .build();
    var context = SecurityContextHolder.createEmptyContext();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    context.setAuthentication(authentication);
    assertThatThrownBy(() -> SessionAttributeCodec.encode(context))
        .isInstanceOf(AuthenticationRequiredException.class);
    ((org.springframework.security.core.CredentialsContainer) user).eraseCredentials();
    authentication.setDetails("synthetic-request-details");
    assertThatThrownBy(() -> SessionAttributeCodec.encode(context))
        .isInstanceOf(AuthenticationRequiredException.class);
    for (String legacy :
        new String[] {
          "v1:ceremony:{}", "v1:authority:" + UUID.randomUUID(), "v1:target:synthetic"
        }) {
      assertThatThrownBy(
              () -> SessionAttributeCodec.decode(legacy.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> SessionAttributeCodec.decode(new byte[] {(byte) 0xac, (byte) 0xed}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SessionAttributeCodec.decode(new byte[8193]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
