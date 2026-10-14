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
                          .maxStringLength(1024)
                          .build())
                  .build())
          .addModules(
              SecurityJacksonModules.getModules(Thread.currentThread().getContextClassLoader()))
          .build();

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
    return encoded.getBytes(StandardCharsets.US_ASCII);
  }

  static Object decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw invalid();
    }
    String encoded = new String(bytes, StandardCharsets.US_ASCII);
    if (encoded.startsWith(CONTEXT_PREFIX)) {
      try {
        var context =
            CONTEXTS.readValue(encoded.substring(CONTEXT_PREFIX.length()), SecurityContext.class);
        AuthenticationIdentity.from(context.getAuthentication());
        return context;
      } catch (JacksonException | AuthenticationRequiredException exception) {
        throw invalid();
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

  private static boolean validToken(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{1,512}");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Unsupported authentication session attribute.");
  }
}
