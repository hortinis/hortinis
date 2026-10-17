package com.hortinis.sync.authentication.http;

import com.hortinis.sync.protocol.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed action contracts use Jackson's duplicate/depth/trailing-input checks after a byte bound.
 */
final class AuthenticationActionBody {
  private static final int MAXIMUM_BYTES = 16384;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(2).maxTokenCount(16).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private AuthenticationActionBody() {}

  static Map<String, String> read(HttpServletRequest request, Set<String> fields)
      throws IOException {
    try {
      var types = Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE));
      if (types.size() != 1 || request.getContentLengthLong() > MAXIMUM_BYTES) {
        throw new InvalidRequestException();
      }
      var media = MediaType.parseMediaType(types.getFirst());
      if (!MediaType.APPLICATION_JSON.getType().equals(media.getType())
          || !MediaType.APPLICATION_JSON.getSubtype().equals(media.getSubtype())
          || media.getParameters().keySet().stream()
              .anyMatch(key -> !"charset".equalsIgnoreCase(key))
          || media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())) {
        throw new InvalidRequestException();
      }
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BYTES + 1);
      if (bytes.length > MAXIMUM_BYTES) {
        throw new InvalidRequestException();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var root = JSON.readTree(text);
      if (root == null || !root.isObject() || root.size() != fields.size()) {
        throw new InvalidRequestException();
      }
      var result = new HashMap<String, String>();
      for (String field : fields) {
        var value = root.get(field);
        if (value == null || !value.isString()) {
          throw new InvalidRequestException();
        }
        result.put(field, value.asString());
      }
      return Map.copyOf(result);
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
      throw new InvalidRequestException();
    }
  }
}
