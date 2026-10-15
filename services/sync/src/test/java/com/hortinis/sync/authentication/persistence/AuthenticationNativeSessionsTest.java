package com.hortinis.sync.authentication.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

class AuthenticationNativeSessionsTest {
  private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  private final UUID account = UUID.randomUUID();

  @SuppressWarnings("unchecked")
  private final FindByIndexNameSessionRepository<MapSession> repository =
      mock(FindByIndexNameSessionRepository.class);

  private final AuthenticationNativeSessions sessions =
      new AuthenticationNativeSessions(
          repository,
          Clock.fixed(NOW, ZoneOffset.UTC),
          mock(org.springframework.jdbc.core.simple.JdbcClient.class));

  @Test
  void listsOnlyCompletedCurrentEpochSessionsWithUsableNonAuthorizingTargets() {
    final var current = session(account, 7, NOW);
    final var stale = session(account, 6, NOW);
    final var foreign = session(UUID.randomUUID(), 7, NOW);
    final var expired = session(account, 7, NOW.minus(AuthenticationLifetime.FULL_ABSOLUTE));
    var inactive = session(account, 7, NOW);
    inactive.setLastAccessedTime(Instant.EPOCH);
    final var partial = new MapSession();
    var missingTarget = session(account, 7, NOW);
    missingTarget.removeAttribute(AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE);
    when(repository.findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, account.toString()))
        .thenReturn(
            Map.of(
                "current",
                current,
                "stale",
                stale,
                "foreign",
                foreign,
                "expired",
                expired,
                "partial",
                partial,
                "missing",
                missingTarget,
                "inactive",
                inactive));

    var listed = sessions.list(account, 7);
    assertThat(listed).hasSize(1);
    assertThat(listed.getFirst().reference())
        .isEqualTo(
            ((SessionAuthorityReference) current.getAttribute("hortinis.authority")).value());
    assertThat(listed.getFirst().expiresAt())
        .isEqualTo(NOW.plus(AuthenticationLifetime.FULL_ABSOLUTE));
    assertThat(listed.getFirst().toString()).isEqualTo("NativeSessionSnapshot[redacted]");
  }

  private static MapSession session(UUID account, long epoch, Instant verifiedAt) {
    var session = new MapSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(AuthenticationIdentity.completed(account, epoch, verifiedAt));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    session.setAttribute("hortinis.authority", new SessionAuthorityReference(UUID.randomUUID()));
    session.setAttribute(
        AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE,
        AuthenticationSecrets.randomValue());
    return session;
  }
}
