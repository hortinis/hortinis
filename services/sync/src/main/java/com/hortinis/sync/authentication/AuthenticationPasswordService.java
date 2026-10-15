package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Spring password authentication with a current snapshot retained for transactional rechecks. */
public final class AuthenticationPasswordService {
  private final AuthenticationManager manager;

  /** One provider owns credential checks, erased credentials and unknown-user dummy work. */
  public AuthenticationPasswordService(
      AuthenticationAccountPersistence accounts, PasswordVerifier encoder) {
    var provider =
        new DaoAuthenticationProvider(
            login ->
                new AccountUser(
                    accounts
                        .findLogin(login)
                        .orElseThrow(
                            () -> new UsernameNotFoundException("Authentication failed."))));
    provider.setPasswordEncoder(encoder);
    manager = new ProviderManager(provider);
  }

  /** Verification is outside publication locks; the caller rechecks the returned exact snapshot. */
  public AuthenticationAccountPersistence.AccountSnapshot authenticate(
      String login, String password) {
    try {
      var result =
          manager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  PasswordPolicy.canonicalLogin(login), password));
      return ((AccountUser) result.getPrincipal()).snapshot;
    } catch (InternalAuthenticationServiceException exception) {
      if (exception.getCause() instanceof DataAccessException unavailable) {
        throw unavailable;
      }
      throw new AuthenticationFailedException();
    } catch (AuthenticationException exception) {
      throw new AuthenticationFailedException();
    }
  }

  private static final class AccountUser extends User {
    private static final long serialVersionUID = 1L;
    private final transient AuthenticationAccountPersistence.AccountSnapshot snapshot;

    private AccountUser(AuthenticationAccountPersistence.AccountSnapshot snapshot) {
      super(snapshot.account().toString(), snapshot.passwordVerifier(), List.of());
      this.snapshot = snapshot;
    }

    @Override
    public String toString() {
      return "AccountUser[redacted]";
    }
  }
}
