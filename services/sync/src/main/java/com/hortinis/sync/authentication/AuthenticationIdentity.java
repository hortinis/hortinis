package com.hortinis.sync.authentication;

import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;

/** Credential-free native principal identity; current storage remains the authority. */
public record AuthenticationIdentity(UUID account, long version) {
  private static final int EXPECTED_VERSION_AUTHORITIES = 1;
  private static final String VERSION = "HORTINIS_VERSION_";

  /** The provider carries the observed security version beside the stable UUID principal. */
  public static String versionAuthority(long version) {
    if (version <= 0) {
      throw new IllegalArgumentException("Invalid security version.");
    }
    return VERSION + version;
  }

  /** Accepts only erased native user identities, never request details or email principals. */
  public static AuthenticationIdentity from(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getClass() != UsernamePasswordAuthenticationToken.class
        || !(authentication.getPrincipal() instanceof User user)
        || user.getClass() != User.class
        || user.getPassword() != null
        || authentication.getCredentials() != null
        || authentication.getDetails() != null) {
      throw new AuthenticationRequiredException();
    }
    try {
      UUID account = UUID.fromString(user.getUsername());
      var versions =
          authentication.getAuthorities().stream()
              .map(authority -> authority.getAuthority())
              .filter(value -> value.startsWith(VERSION))
              .toList();
      if (versions.size() != EXPECTED_VERSION_AUTHORITIES) {
        throw new AuthenticationRequiredException();
      }
      String value = versions.getFirst().substring(VERSION.length());
      long version = Long.parseLong(value);
      if (!account.toString().equals(user.getUsername())
          || version <= 0
          || !Long.toString(version).equals(value)) {
        throw new AuthenticationRequiredException();
      }
      return new AuthenticationIdentity(account, version);
    } catch (IllegalArgumentException exception) {
      throw new AuthenticationRequiredException();
    }
  }

  @Override
  public String toString() {
    return "AuthenticationIdentity[redacted]";
  }
}
