package com.hortinis.sync.access;

import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Maps current opaque servlet transport to owned identity; every data transaction rechecks it. */
public final class AuthenticatedSynchronizationAccessResolver
    implements SynchronizationAccessResolver {
  private final CurrentAuthenticationAuthorityPersistence authority;

  /**
   * Current persistence is the sole source of account/scope/epoch; request identity fields are
   * ignored.
   */
  public AuthenticatedSynchronizationAccessResolver(
      CurrentAuthenticationAuthorityPersistence authority) {
    this.authority = authority;
  }

  @Override
  public SynchronizationAccessContext resolve() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      throw new AuthenticationRequiredException();
    }
    var session = attributes.getRequest().getSession(false);
    if (session == null
        || !AuthenticationSecrets.isRandomValue(session.getId())
        || !(session.getAttribute("hortinis.authority")
            instanceof SessionAuthorityReference reference)) {
      throw new AuthenticationRequiredException();
    }
    var current =
        authority
            .findFull(AuthenticationSecrets.digest(session.getId()), reference.value())
            .orElseThrow(AuthenticationRequiredException::new);
    if (!ServerAccessMode.SINGLE_USER_AUTH.wireValue().equals(current.mode())) {
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
