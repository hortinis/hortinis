package com.hortinis.sync.protocol;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SyncCursorCodec {

  private static final String PREFIX = "v1.";
  private static final BigInteger ZERO = BigInteger.ZERO;
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  private SyncCursorCodec() {}

  public static long decode(String cursor) {
    if (cursor == null) {
      return 0;
    }
    if (cursor.isEmpty() || !cursor.startsWith(PREFIX)) {
      throw new InvalidRequestException();
    }
    try {
      String encodedPosition = cursor.substring(PREFIX.length());
      if (encodedPosition.isEmpty()) {
        throw new InvalidRequestException();
      }
      String position =
          new String(Base64.getUrlDecoder().decode(encodedPosition), StandardCharsets.UTF_8);
      BigInteger parsed = new BigInteger(position);
      if (parsed.compareTo(ZERO) < 0 || parsed.compareTo(LONG_MAX) > 0) {
        throw new InvalidRequestException();
      }
      if (!encode(parsed.longValueExact()).equals(cursor)) {
        throw new InvalidRequestException();
      }
      return parsed.longValueExact();
    } catch (IllegalArgumentException | ArithmeticException exception) {
      throw new InvalidRequestException();
    }
  }

  public static String encode(long sequence) {
    if (sequence < 0) {
      throw new IllegalArgumentException("A synchronization cursor cannot be negative.");
    }
    String position = Long.toString(sequence);
    return PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(position.getBytes(StandardCharsets.UTF_8));
  }
}
