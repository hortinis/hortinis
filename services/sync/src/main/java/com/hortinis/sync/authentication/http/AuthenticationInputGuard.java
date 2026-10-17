package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationAttemptLimiter;
import com.hortinis.sync.authentication.EmailAddress;
import com.hortinis.sync.authentication.PasswordPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounded syntax/admission only; Spring's unchanged native filter authenticates the supplied form.
 */
public final class AuthenticationInputGuard extends OncePerRequestFilter {
  private static final char ESCAPE = '%';
  private static final int ASCII_MAXIMUM = 127;
  private static final int MAX_BYTES = 16384;
  private final AuthenticationAttemptLimiter attempts;

  /** Request admission happens before any expensive provider work or servlet form parsing. */
  public AuthenticationInputGuard(AuthenticationAttemptLimiter attempts) {
    this.attempts = attempts;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpServletRequest validated = request;
    try {
      if (AuthenticationRoutes.ALL.matches(request)) {
        if (request.getQueryString() != null || request.getRequestURI().contains("%")) {
          throw new IllegalArgumentException();
        }
        if (AuthenticationRoutes.LOGIN.matches(request)) {
          var parameters = form(request);
          String email = EmailAddress.canonical(parameters.get("email"));
          if (!PasswordPolicy.isValidLoginText(parameters.get("password"))) {
            throw new IllegalArgumentException();
          }
          if (!attempts.admit(email, request.getRemoteAddr())) {
            AuthenticationHttpErrors.THROTTLED.write(response);
            return;
          }
          validated = new ValidatedForm(request, parameters);
        } else if (AuthenticationRoutes.LOGOUT.matches(request)
            && request.getInputStream().read() != -1) {
          throw new IllegalArgumentException();
        }
      }
    } catch (IllegalArgumentException | IOException exception) {
      AuthenticationHttpErrors.INVALID.write(response);
      return;
    }
    chain.doFilter(validated, response);
  }

  private static Map<String, String> form(HttpServletRequest request) throws IOException {
    var types = Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE));
    if (types.size() != 1 || request.getContentLengthLong() > MAX_BYTES) {
      throw new IllegalArgumentException();
    }
    var type = MediaType.parseMediaType(types.getFirst());
    if (!"application".equals(type.getType())
        || !"x-www-form-urlencoded".equals(type.getSubtype())
        || !Set.of("charset").containsAll(type.getParameters().keySet())
        || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) {
      throw new IllegalArgumentException();
    }
    byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException();
    }
    String raw = strictText(bytes);
    var result = new HashMap<String, String>();
    for (String field : raw.split("&", -1)) {
      int equals = field.indexOf('=');
      if (equals < 0 || field.indexOf('=', equals + 1) >= 0) {
        throw new IllegalArgumentException();
      }
      String key = decode(field.substring(0, equals));
      if (!Set.of("email", "password").contains(key)
          || result.putIfAbsent(key, decode(field.substring(equals + 1))) != null) {
        throw new IllegalArgumentException();
      }
    }
    if (!result.keySet().equals(Set.of("email", "password"))) {
      throw new IllegalArgumentException();
    }
    return Map.copyOf(result);
  }

  private static String decode(String encoded) throws CharacterCodingException {
    var result = new ByteArrayOutputStream();
    for (int offset = 0; offset < encoded.length(); offset++) {
      char value = encoded.charAt(offset);
      if (value == ESCAPE) {
        if (offset + 2 >= encoded.length()) {
          throw new IllegalArgumentException();
        }
        int high = hex(encoded.charAt(offset + 1));
        int low = hex(encoded.charAt(offset + 2));
        if (high < 0 || low < 0) {
          throw new IllegalArgumentException();
        }
        offset += 2;
        result.write(high * 16 + low);
      } else if (value > ASCII_MAXIMUM) {
        throw new IllegalArgumentException();
      } else {
        result.write(value == '+' ? ' ' : value);
      }
    }
    return strictText(result.toByteArray());
  }

  private static int hex(char value) {
    return "0123456789abcdef"
        .indexOf(value >= 'A' && value <= 'F' ? (char) (value + ('a' - 'A')) : value);
  }

  private static String strictText(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static final class ValidatedForm extends HttpServletRequestWrapper {
    private final Map<String, String> fields;

    private ValidatedForm(HttpServletRequest request, Map<String, String> fields) {
      super(request);
      this.fields = fields;
    }

    @Override
    public String getParameter(String name) {
      return fields.get(name);
    }

    @Override
    public String[] getParameterValues(String name) {
      return fields.containsKey(name) ? new String[] {fields.get(name)} : null;
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return Collections.enumeration(fields.keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      var result = new HashMap<String, String[]>();
      fields.forEach((key, value) -> result.put(key, new String[] {value}));
      return Collections.unmodifiableMap(result);
    }
  }
}
