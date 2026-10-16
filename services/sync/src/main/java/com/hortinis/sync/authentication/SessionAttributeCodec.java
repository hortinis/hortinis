package com.hortinis.sync.authentication;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** Scalar attributes and native Spring JSON security contexts; no Java object deserialization. */
public final class SessionAttributeCodec {
  private static final String CSRF_PREFIX = "v1:csrf:";
  private static final String REFERENCE_PREFIX = "v1:authority:";
  private static final String TARGET_PREFIX = "v1:target:";
  private static final String CEREMONY_PREFIX = "v1:ceremony:";
  private static final String ACCOUNT_PREFIX = "v1:account:";
  private static final String CONTEXT_PREFIX = "v1:security:";
  private static final int MAX_BYTES = 8192;
  private static final JsonMapper CONTEXTS =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxNestingDepth(16)
                          .maxTokenCount(256)
                          .maxStringLength(4096)
                          .build())
                  .build())
          .addModules(
              SecurityJacksonModules.getModules(Thread.currentThread().getContextClassLoader()))
          .build();

  private static final JsonMapper CEREMONIES =
      JsonMapper.builder(CONTEXTS.tokenStreamFactory()).build();

  private SessionAttributeCodec() {}

  /** Overrides both directions used by Spring Session JDBC's attribute adapter. */
  public static ConversionService conversionService() {
    GenericConversionService conversion = new GenericConversionService();
    conversion.addConverter(Object.class, byte[].class, SessionAttributeCodec::encode);
    conversion.addConverter(byte[].class, Object.class, SessionAttributeCodec::decode);
    return conversion;
  }

  static byte[] encode(Object attribute) {
    String encoded;
    if (attribute instanceof SecurityContext context) {
      AuthenticationIdentity.from(context.getAuthentication());
      encoded = CONTEXT_PREFIX + CONTEXTS.writeValueAsString(context);
      if (encoded.length() > MAX_BYTES) {
        throw invalid();
      }
    } else if (attribute instanceof AuthenticationSession ceremony) {
      requireCeremony(ceremony);
      encoded = CEREMONY_PREFIX + CEREMONIES.writeValueAsString(ceremony);
    } else if (attribute instanceof String account && account.matches("[a-f0-9-]{36}")) {
      encoded = ACCOUNT_PREFIX + UUID.fromString(account);
    } else if (attribute instanceof String target && AuthenticationSecrets.isRandomValue(target)) {
      encoded = TARGET_PREFIX + target;
    } else if (attribute instanceof SessionAuthorityReference reference) {
      encoded = REFERENCE_PREFIX + reference.value();
    } else if (attribute instanceof CsrfToken csrf
        && "X-CSRF-TOKEN".equals(csrf.getHeaderName())
        && "_csrf".equals(csrf.getParameterName())
        && validToken(csrf.getToken())) {
      encoded = CSRF_PREFIX + csrf.getToken();
    } else {
      throw invalid();
    }
    if (encoded.length() > MAX_BYTES) {
      throw invalid();
    }
    byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) {
      throw invalid();
    }
    return bytes;
  }

  static Object decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw invalid();
    }
    String encoded = new String(bytes, StandardCharsets.UTF_8);
    if (encoded.startsWith(CEREMONY_PREFIX)) {
      try {
        var ceremony =
            CEREMONIES.readValue(
                encoded.substring(CEREMONY_PREFIX.length()), AuthenticationSession.class);
        requireCeremony(ceremony);
        return ceremony;
      } catch (JacksonException exception) {
        throw invalid();
      }
    } else if (encoded.startsWith(ACCOUNT_PREFIX)) {
      String account = encoded.substring(ACCOUNT_PREFIX.length());
      try {
        if (UUID.fromString(account).toString().equals(account)) {
          return account;
        }
      } catch (IllegalArgumentException exception) {
        throw invalid();
      }
      throw invalid();
    } else if (encoded.startsWith(CONTEXT_PREFIX)) {
      try {
        var context =
            CONTEXTS.readValue(encoded.substring(CONTEXT_PREFIX.length()), SecurityContext.class);
        AuthenticationIdentity.from(context.getAuthentication());
        return context;
      } catch (JacksonException | AuthenticationRequiredException exception) {
        throw invalid();
      }
    } else if (encoded.startsWith(TARGET_PREFIX)) {
      String target = encoded.substring(TARGET_PREFIX.length());
      if (AuthenticationSecrets.isRandomValue(target)) {
        return target;
      }
    } else if (encoded.startsWith(CSRF_PREFIX)) {
      String token = encoded.substring(CSRF_PREFIX.length());
      if (validToken(token)) {
        return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", token);
      }
    } else if (encoded.startsWith(REFERENCE_PREFIX)) {
      String reference = encoded.substring(REFERENCE_PREFIX.length());
      try {
        UUID value = UUID.fromString(reference);
        if (value.toString().equals(reference)) {
          return new SessionAuthorityReference(value);
        }
      } catch (IllegalArgumentException exception) {
        throw invalid();
      }
    }
    throw invalid();
  }

  private static void requireCeremony(AuthenticationSession ceremony) {
    if (ceremony == null
        || ceremony.state() == null
        || ceremony.reference() == null
        || ceremony.createdAt() == null
        || ceremony.expiresAt() == null
        || !java.util.Set.of("anonymous", "password", "setup", "recovery")
            .contains(ceremony.state())
        || !ceremony.expiresAt().isAfter(ceremony.createdAt())
        || ceremony
            .expiresAt()
            .isAfter(ceremony.createdAt().plus(AuthenticationLifetime.LIMITED_ABSOLUTE))
        || (ceremony.account() == null ? ceremony.epoch() != 0 : ceremony.epoch() <= 0)
        || "password".equals(ceremony.state()) && ceremony.account() == null
        || !AuthenticationSecrets.isRandomValue(ceremony.logoutTarget())) {
      throw invalid();
    }
  }

  private static boolean validToken(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{1,512}");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Unsupported authentication session attribute.");
  }
}
