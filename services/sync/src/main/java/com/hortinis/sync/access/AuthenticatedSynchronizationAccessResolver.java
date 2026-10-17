package com.hortinis.sync.access;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Maps Spring's current authenticated principal to the framework-independent sync context. */
public final class AuthenticatedSynchronizationAccessResolver
    implements SynchronizationAccessResolver {
  private final CurrentAuthenticationAuthorityPersistence authority;

  /** Keeps current ownership and revocation checks while native Spring sessions are introduced. */
  public AuthenticatedSynchronizationAccessResolver(
      CurrentAuthenticationAuthorityPersistence authority) {
    this.authority = authority;
  }

  @Override
  public SynchronizationAccessContext resolve() {
    var identity =
        AuthenticationIdentity.from(SecurityContextHolder.getContext().getAuthentication());
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      throw new AuthenticationRequiredException();
    }
    var session = attributes.getRequest().getSession(false);
    if (session == null || !AuthenticationSecrets.isRandomValue(session.getId())) {
      throw new AuthenticationRequiredException();
    }
    CurrentAuthenticationAuthorityPersistence.CurrentAuthority current;
    try {
      current =
          authority
              .find(session.getId(), identity)
              .filter(
                  value ->
                      value.account().equals(identity.account())
                          && value.version() == identity.version())
              .filter(value -> ServerAccessMode.SINGLE_USER_AUTH.wireValue().equals(value.mode()))
              .orElseThrow(AuthenticationRequiredException::new);
    } catch (DataAccessException | TransactionException exception) {
      SecurityContextHolder.clearContext();
      throw new SynchronizationAccessUnavailableException();
    }
    return SynchronizationAccessContext.authenticated(
        new ServerInstanceId(current.instance().toString()),
        new SynchronizationScopeId(current.scope().toString()),
        ServerAccessMode.SINGLE_USER_AUTH,
        new AccountId(current.account().toString()),
        current.sessionReference(),
        current.version());
  }
}
