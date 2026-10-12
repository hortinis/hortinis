package com.hortinis.sync.authentication;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** Closed scalar JDBC session encoding; no Java deserialization or arbitrary principal graph. */
public final class SessionAttributeCodec {
  private static final String CSRF_PREFIX = "v1:csrf:";
  private static final String REFERENCE_PREFIX = "v1:authority:";
  private static final int MAX_BYTES = 1024;

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
    if (attribute instanceof SessionAuthorityReference reference) {
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
    if (encoded.startsWith(CSRF_PREFIX)) {
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
