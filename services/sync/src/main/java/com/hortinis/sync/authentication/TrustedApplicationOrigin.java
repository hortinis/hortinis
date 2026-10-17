package com.hortinis.sync.authentication;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** One trusted HTTPS origin policy for browser request checks and action links. */
public final class TrustedApplicationOrigin {
  private static final String INVALID = "A trusted HTTPS application origin is required.";

  private TrustedApplicationOrigin() {}

  /** Canonicalizes trusted configuration only; request and forwarded headers are never inputs. */
  public static URI parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException(INVALID);
    }
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || host == null
          || host.contains(":")
          || host.endsWith(".")
          || host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
          || uri.getUserInfo() != null
          || !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || uri.getRawAuthority().endsWith(":")) {
        throw new IllegalArgumentException(INVALID);
      }
      return new URI(
          "https",
          null,
          host.toLowerCase(Locale.ROOT),
          uri.getPort() == 443 ? -1 : uri.getPort(),
          "",
          null,
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException(INVALID);
    }
  }
}
