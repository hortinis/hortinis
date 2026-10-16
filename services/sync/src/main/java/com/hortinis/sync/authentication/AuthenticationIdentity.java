package com.hortinis.sync.authentication;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Application identity carried by Spring's credential-free principal and factor authorities. */
public record AuthenticationIdentity(UUID account, long epoch) {
  private static final String EPOCH = "HORTINIS_EPOCH_";

  /** Both proofs were completed by the owning workflow before this native context is published. */
  public static Authentication completed(UUID account, long epoch, Instant verifiedAt) {
    return factors(account, epoch, verifiedAt, true);
  }

  /** Password-only authentication cannot satisfy the required WebAuthn factor. */
  public static Authentication password(UUID account, long epoch, Instant verifiedAt) {
    return factors(account, epoch, verifiedAt, false);
  }

  private static Authentication factors(
      UUID account, long epoch, Instant verifiedAt, boolean full) {
    var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
    authorities.add(new SimpleGrantedAuthority(EPOCH + epoch));
    authorities.add(
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
            .issuedAt(verifiedAt)
            .build());
    if (full) {
      authorities.add(
          FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
              .issuedAt(verifiedAt)
              .build());
    }
    var user = new User(account.toString(), "", authorities);
    user.eraseCredentials();
    return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
  }

  /**
   * Reads only native, authenticated, credential-free identities; wire fields cannot select one.
   */
  public static AuthenticationIdentity from(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof User user)
        || user.getPassword() != null
        || authentication.getCredentials() != null
        || authentication.getDetails() != null) {
      throw new AuthenticationRequiredException();
    }
    try {
      UUID account = UUID.fromString(user.getUsername());
      String epoch =
          authentication.getAuthorities().stream()
              .map(authority -> authority.getAuthority())
              .filter(value -> value.startsWith(EPOCH))
              .findFirst()
              .orElseThrow(AuthenticationRequiredException::new)
              .substring(EPOCH.length());
      long version = Long.parseLong(epoch);
      if (!account.toString().equals(user.getUsername())
          || version <= 0
          || !Long.toString(version).equals(epoch)) {
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
