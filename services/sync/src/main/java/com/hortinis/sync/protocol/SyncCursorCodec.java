package com.hortinis.sync.protocol;

import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationScopeId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Generationless, canonical cursor fenced to a server installation and scope. */
public final class SyncCursorCodec {
  private static final String PREFIX = "scope-v1.";
  private static final int MAX_CURSOR_LENGTH = 160;

  private SyncCursorCodec() {}

  /** Rejects foreign, legacy, malformed, and non-canonical boundaries. */
  public static long decode(
      String cursor, ServerInstanceId instance, SynchronizationScopeId scope) {
    if (cursor == null) {
      return 0;
    }
    if (cursor.length() > MAX_CURSOR_LENGTH || !cursor.startsWith(PREFIX)) {
      throw new InvalidRequestException();
    }
    try {
      String decoded =
          new String(
              Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length())),
              StandardCharsets.UTF_8);
      String[] fields = decoded.split(":", -1);
      if (fields.length != 3
          || !fields[0].equals(instance.value())
          || !fields[1].equals(scope.value())) {
        throw new InvalidRequestException();
      }
      long sequence = Long.parseLong(fields[2]);
      if (sequence < 0 || !encode(sequence, instance, scope).equals(cursor)) {
        throw new InvalidRequestException();
      }
      return sequence;
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException();
    }
  }

  /** Encodes non-negative positions within one installation and scope. */
  public static String encode(
      long sequence, ServerInstanceId instance, SynchronizationScopeId scope) {
    if (sequence < 0) {
      throw new IllegalArgumentException("A synchronization cursor cannot be negative.");
    }
    String boundary = instance.value() + ":" + scope.value() + ":" + sequence;
    return PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(boundary.getBytes(StandardCharsets.UTF_8));
  }
}
