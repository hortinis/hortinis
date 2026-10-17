package com.hortinis.sync.authentication;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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

/** Closed scalar attributes and native security JSON; no Java object deserialization. */
public final class SessionAttributeCodec {
  private static final String CSRF = "v2:csrf:";
  private static final String ACCOUNT = "v2:account:";
  private static final String CONTEXT = "v2:security:";
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

  private SessionAttributeCodec() {}

  /** Overrides both directions used by the Boot JDBC session repository. */
  public static ConversionService conversionService() {
    var conversion = new GenericConversionService();
    conversion.addConverter(Object.class, byte[].class, SessionAttributeCodec::encode);
    conversion.addConverter(byte[].class, Object.class, SessionAttributeCodec::decode);
    return conversion;
  }

  static byte[] encode(Object attribute) {
    String encoded;
    if (attribute instanceof SecurityContext context) {
      AuthenticationIdentity.from(context.getAuthentication());
      encoded = CONTEXT + CONTEXTS.writeValueAsString(context);
    } else if (attribute instanceof String account
        && UUID.fromString(account).toString().equals(account)) {
      encoded = ACCOUNT + account;
    } else if (attribute instanceof CsrfToken csrf
        && "X-CSRF-TOKEN".equals(csrf.getHeaderName())
        && "_csrf".equals(csrf.getParameterName())
        && validToken(csrf.getToken())) {
      encoded = CSRF + csrf.getToken();
    } else {
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
    try {
      String encoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (encoded.startsWith(CONTEXT)) {
        var context =
            CONTEXTS.readValue(encoded.substring(CONTEXT.length()), SecurityContext.class);
        AuthenticationIdentity.from(context.getAuthentication());
        return context;
      } else if (encoded.startsWith(ACCOUNT)) {
        String account = encoded.substring(ACCOUNT.length());
        if (UUID.fromString(account).toString().equals(account)) {
          return account;
        }
      } else if (encoded.startsWith(CSRF)) {
        String token = encoded.substring(CSRF.length());
        if (validToken(token)) {
          return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", token);
        }
      }
    } catch (CharacterCodingException
        | JacksonException
        | AuthenticationRequiredException exception) {
      throw invalid();
    }
    throw invalid();
  }

  private static boolean validToken(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{1,256}");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Unsupported authentication session attribute.");
  }
}
