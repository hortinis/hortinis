package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Repository;

/** Completed sessions are discovered through Spring's native principal index and factor policy. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationNativeSessions {
  private final FindByIndexNameSessionRepository<? extends Session> repository;
  private final AllRequiredFactorsAuthorizationManager<Session> factors;

  /** Uses the Boot-owned repository and trusted application clock. */
  public AuthenticationNativeSessions(
      @Lazy FindByIndexNameSessionRepository<? extends Session> repository, Clock clock) {
    this.repository = repository;
    factors =
        AllRequiredFactorsAuthorizationManager.<Session>builder()
            .requireFactor(
                factor ->
                    factor.passwordAuthority().validDuration(AuthenticationLifetime.FULL_ABSOLUTE))
            .requireFactor(
                factor ->
                    factor.webauthnAuthority().validDuration(AuthenticationLifetime.FULL_ABSOLUTE))
            .build();
    factors.setClock(clock);
  }

  /** Partial, expired and stale-epoch contexts never consume completed-session capacity. */
  public List<Snapshot> list(UUID account, long epoch) {
    return repository
        .findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, account.toString())
        .values()
        .stream()
        .filter(session -> !session.isExpired())
        .map(session -> snapshot(session, account, epoch))
        .filter(java.util.Objects::nonNull)
        .sorted(
            java.util.Comparator.comparing(Snapshot::createdAt).thenComparing(Snapshot::reference))
        .toList();
  }

  private Snapshot snapshot(Session session, UUID account, long epoch) {
    SecurityContext context =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    if (context == null || !factors.authorize(context::getAuthentication, session).isGranted()) {
      return null;
    }
    try {
      var identity = AuthenticationIdentity.from(context.getAuthentication());
      if (!identity.account().equals(account) || identity.epoch() != epoch) {
        return null;
      }
      SessionAuthorityReference reference = session.getAttribute("hortinis.authority");
      String target = session.getAttribute(AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE);
      if (reference == null || !AuthenticationSecrets.isRandomValue(target)) {
        return null;
      }
      Instant expires =
          context.getAuthentication().getAuthorities().stream()
              .filter(FactorGrantedAuthority.class::isInstance)
              .map(FactorGrantedAuthority.class::cast)
              .map(factor -> factor.getIssuedAt().plus(AuthenticationLifetime.FULL_ABSOLUTE))
              .min(Instant::compareTo)
              .orElseThrow(AuthenticationRequiredException::new);
      return new Snapshot(reference.value(), target, session.getCreationTime(), expires);
    } catch (AuthenticationRequiredException exception) {
      return null;
    }
  }

  /** Contains no native cookie identifier or credential material. */
  public record Snapshot(UUID reference, String target, Instant createdAt, Instant expiresAt) {
    @Override
    public String toString() {
      return "NativeSessionSnapshot[redacted]";
    }
  }
}
