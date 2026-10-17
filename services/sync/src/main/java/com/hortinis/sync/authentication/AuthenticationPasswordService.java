package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Small native-provider lookup: email input, stable UUID and current version output. */
public final class AuthenticationPasswordService implements UserDetailsService {
  private final AuthenticationAccountPersistence accounts;

  /** The native DaoAuthenticationProvider owns verification, dummy work and credential erasure. */
  public AuthenticationPasswordService(AuthenticationAccountPersistence accounts) {
    this.accounts = accounts;
  }

  @Override
  public UserDetails loadUserByUsername(String email) {
    var account =
        accounts
            .findLogin(email)
            .orElseThrow(() -> new UsernameNotFoundException("Authentication failed."));
    return User.withUsername(account.account().toString())
        .password(account.passwordVerifier())
        .authorities(AuthenticationIdentity.versionAuthority(account.version()))
        .build();
  }
}
