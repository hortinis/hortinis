package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthenticatedSynchronizationAccessResolverTest {
  private static final UUID REFERENCE = UUID.randomUUID();
  private static final String IDENTIFIER = AuthenticationSecrets.randomValue();
  private static final String ATTRIBUTE = "hortinis.authority";
  private final CurrentAuthenticationAuthorityPersistence authority =
      mock(CurrentAuthenticationAuthorityPersistence.class);
  private final AuthenticatedSynchronizationAccessResolver resolver =
      new AuthenticatedSynchronizationAccessResolver(authority);

  @AfterEach
  void releaseRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void missingRequestOrSessionDeniesWithoutAllocationOrConfiguredFallback() {
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    assertThat(request.getSession(false)).isNull();
    verifyNoInteractions(authority);
  }

  @Test
  void rawTransportAndUntypedSavedPrincipalCannotGrantAuthority() {
    var session = requestSession();
    when(session.getId()).thenReturn("untrusted-session-identifier");
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    when(session.getId()).thenReturn(IDENTIFIER);
    when(session.getAttribute(ATTRIBUTE)).thenReturn(REFERENCE.toString());
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    verifyNoInteractions(authority);
  }

  @Test
  void partialExpiredRevokedOrWrongModeCurrentAuthorityCannotBecomeNoAuth() {
    requestSession();
    when(authority.findFull(AuthenticationSecrets.digest(IDENTIFIER), REFERENCE))
        .thenReturn(Optional.empty());
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    when(authority.findFull(AuthenticationSecrets.digest(IDENTIFIER), REFERENCE))
        .thenReturn(Optional.of(current("single-user-no-auth")));
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  void identityIsDerivedOnlyFromCurrentPersistentAuthorityAndEveryResolutionRechecks() {
    var session = requestSession();
    var current = current("single-user-auth");
    when(authority.findFull(AuthenticationSecrets.digest(IDENTIFIER), REFERENCE))
        .thenReturn(Optional.of(current));
    var resolved = resolver.resolve();
    assertThat(resolved.serverInstanceId().value()).isEqualTo(current.instance().toString());
    assertThat(resolved.synchronizationScopeId().value()).isEqualTo(current.scope().toString());
    var full = (SynchronizationAccessContext.AuthenticatedAuthority) resolved.authority();
    assertThat(full.accountId().value()).isEqualTo(current.account().toString());
    assertThat(full.sessionReference()).isEqualTo(REFERENCE);
    assertThat(full.authorityEpoch()).isEqualTo(7);
    assertThat(resolved.toString()).isEqualTo("SynchronizationAccessContext[redacted]");
    when(authority.findFull(AuthenticationSecrets.digest(IDENTIFIER), REFERENCE))
        .thenReturn(Optional.empty());
    assertThatThrownBy(resolver::resolve).isInstanceOf(AuthenticationRequiredException.class);
    verify(session, org.mockito.Mockito.times(2)).getAttribute(ATTRIBUTE);
  }

  private static HttpSession requestSession() {
    var request = mock(jakarta.servlet.http.HttpServletRequest.class);
    var session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getId()).thenReturn(IDENTIFIER);
    when(session.getAttribute(ATTRIBUTE)).thenReturn(new SessionAuthorityReference(REFERENCE));
    when(request.getHeader("X-Account-Id")).thenReturn(UUID.randomUUID().toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return session;
  }

  private static CurrentAuthenticationAuthorityPersistence.FullAuthority current(String mode) {
    Instant now = Instant.parse("2026-10-12T06:00:00Z");
    return new CurrentAuthenticationAuthorityPersistence.FullAuthority(
        REFERENCE,
        UUID.randomUUID(),
        7,
        UUID.randomUUID(),
        UUID.randomUUID(),
        mode,
        now,
        now,
        now,
        now.plusSeconds(43_200),
        now);
  }
}
