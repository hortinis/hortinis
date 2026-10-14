package com.hortinis.sync.access;

import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import org.springframework.security.core.context.SecurityContextHolder;

/** Maps Spring's current authenticated principal to the framework-independent sync context. */
public final class AuthenticatedSynchronizationAccessResolver
    implements SynchronizationAccessResolver {
  @Override
  public SynchronizationAccessContext resolve() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal()
            instanceof CurrentAuthenticationAuthorityPersistence.FullAuthority current)
        || !ServerAccessMode.SINGLE_USER_AUTH.wireValue().equals(current.mode())) {
      throw new AuthenticationRequiredException();
    }
    return SynchronizationAccessContext.authenticated(
        new ServerInstanceId(current.instance().toString()),
        new SynchronizationScopeId(current.scope().toString()),
        ServerAccessMode.SINGLE_USER_AUTH,
        new AccountId(current.account().toString()),
        current.sessionReference(),
        current.epoch());
  }
}
