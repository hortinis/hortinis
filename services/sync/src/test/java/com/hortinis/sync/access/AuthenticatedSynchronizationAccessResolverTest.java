package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthenticatedSynchronizationAccessResolverTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID REFERENCE = UUID.randomUUID();
  private static final String IDENTIFIER = AuthenticationSecrets.randomValue();
  private final CurrentAuthenticationAuthorityPersistence authority =
      mock(CurrentAuthenticationAuthorityPersistence.class);
  private final AuthenticatedSynchronizationAccessResolver resolver =
      new AuthenticatedSynchronizationAccessResolver(authority);
  private final HttpSessionSecurityContextRepository contexts =
      new HttpSessionSecurityContextRepository();

  @AfterEach
  void releaseRequest() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  @Test
  void missingRequestOrSessionDeniesWithoutAllocationOrConfiguredFallback() {
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
    assertThat(request.getSession(false)).isNull();
    verifyNoInteractions(authority);
  }

  @Test
  void rawTransportAndUntypedSavedPrincipalCannotGrantAuthority() {
    var session = requestSession();
    when(session.getId()).thenReturn("untrusted-session-identifier");
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
    when(session.getId()).thenReturn(IDENTIFIER);
    var invalid = SecurityContextHolder.createEmptyContext();
    invalid.setAuthentication(
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated("untyped", null, java.util.List.of()));
    when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
        .thenReturn(invalid);
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
    verifyNoInteractions(authority);
  }

  @Test
  void partialExpiredRevokedOrWrongModeCurrentAuthorityCannotBecomeNoAuth() {
    requestSession();
    when(authority.find(IDENTIFIER, new AuthenticationIdentity(ACCOUNT, 7)))
        .thenReturn(Optional.empty());
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
    when(authority.find(IDENTIFIER, new AuthenticationIdentity(ACCOUNT, 7)))
        .thenReturn(Optional.of(current("single-user-no-auth")));
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  void identityIsDerivedOnlyFromCurrentPersistentAuthorityAndEveryResolutionRechecks() {
    var session = requestSession();
    var current = current("single-user-auth");
    when(authority.find(IDENTIFIER, new AuthenticationIdentity(ACCOUNT, 7)))
        .thenReturn(Optional.of(current));
    var resolved = resolve();
    assertThat(resolved.serverInstanceId().value()).isEqualTo(current.instance().toString());
    assertThat(resolved.synchronizationScopeId().value()).isEqualTo(current.scope().toString());
    var full = (SynchronizationAccessContext.AuthenticatedAuthority) resolved.authority();
    assertThat(full.accountId().value()).isEqualTo(current.account().toString());
    assertThat(full.sessionReference()).isEqualTo(REFERENCE);
    assertThat(full.authorityEpoch()).isEqualTo(7);
    assertThat(resolved.toString()).isEqualTo("SynchronizationAccessContext[redacted]");
    when(authority.find(IDENTIFIER, new AuthenticationIdentity(ACCOUNT, 7)))
        .thenReturn(Optional.empty());
    assertThatThrownBy(this::resolve).isInstanceOf(AuthenticationRequiredException.class);
  }

  private SynchronizationAccessContext resolve() {
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      SecurityContextHolder.setContext(
          contexts.loadContext(
              new HttpRequestResponseHolder(attributes.getRequest(), attributes.getResponse())));
    }
    return resolver.resolve();
  }

  @Test
  void unavailableStorageFailsClosedWithoutRetainingThePriorPrincipal() {
    requestSession();
    when(authority.find(IDENTIFIER, new AuthenticationIdentity(ACCOUNT, 7)))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "private database details"));
    assertThatThrownBy(this::resolve).isInstanceOf(SynchronizationAccessUnavailableException.class);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  private static HttpSession requestSession() {
    var request = new MockHttpServletRequest();
    var session = mock(HttpSession.class);
    request.setSession(session);
    when(session.getId()).thenReturn(IDENTIFIER);
    var context = SecurityContextHolder.createEmptyContext();
    var user =
        org.springframework.security.core.userdetails.User.withUsername(ACCOUNT.toString())
            .password("")
            .authorities(AuthenticationIdentity.versionAuthority(7))
            .build();
    ((org.springframework.security.core.CredentialsContainer) user).eraseCredentials();
    context.setAuthentication(
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated(user, null, user.getAuthorities()));
    when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
        .thenReturn(context);
    request.addHeader("X-Account-Id", UUID.randomUUID().toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return session;
  }

  private static CurrentAuthenticationAuthorityPersistence.CurrentAuthority current(String mode) {
    return new CurrentAuthenticationAuthorityPersistence.CurrentAuthority(
        REFERENCE, ACCOUNT, 7, UUID.randomUUID(), UUID.randomUUID(), mode);
  }
}
