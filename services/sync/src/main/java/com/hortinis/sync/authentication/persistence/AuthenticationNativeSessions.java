package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSession;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Repository;

/** Native session lookup, principal indexing and completed-factor policy. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationNativeSessions {
  private static final String PASSWORD_STATE = "password";
  private final JdbcClient jdbc;
  private final Clock clock;
  private final FindByIndexNameSessionRepository<? extends Session> repository;
  private final AllRequiredFactorsAuthorizationManager<Session> factors;

  /** Uses the Boot-owned repository and trusted application clock. */
  public AuthenticationNativeSessions(
      @Lazy FindByIndexNameSessionRepository<? extends Session> repository,
      Clock clock,
      JdbcClient jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.clock = clock;
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
        .map(this::snapshot)
        .filter(java.util.Objects::nonNull)
        .filter(session -> session.account().equals(account) && session.epoch() == epoch)
        .sorted(
            java.util.Comparator.comparing(Snapshot::createdAt).thenComparing(Snapshot::reference))
        .toList();
  }

  /** Row locks follow the caller's account lock; the cookie identifier remains internal. */
  public Optional<AuthenticationSession> findFull(UUID reference, String verifier, Lock lock) {
    return find(reference, verifier, lock).filter(session -> "full".equals(session.state()));
  }

  /** One native row owns both restricted workflow data and completed authentication. */
  public Optional<AuthenticationSession> find(UUID reference, String verifier, Lock lock) {
    if (reference == null) {
      return Optional.empty();
    }
    var identifier =
        jdbc.sql(
                "SELECT s.session_id FROM spring_session s JOIN spring_session_attributes a "
                    + "ON a.session_primary_id = s.primary_id WHERE a.attribute_name = "
                    + "'hortinis.authority' "
                    + "AND a.attribute_bytes = ?"
                    + lock.sql)
            .param(
                SessionAttributeCodec.conversionService()
                    .convert(new SessionAuthorityReference(reference), byte[].class))
            .query(String.class)
            .optional();
    if (identifier.isEmpty()
        || verifier != null
            && !com.hortinis.sync.authentication.AuthenticationSecrets.digest(
                    identifier.orElseThrow())
                .equals(verifier)) {
      return Optional.empty();
    }
    Session session = repository.findById(identifier.orElseThrow());
    if (session == null) {
      return Optional.empty();
    }
    AuthenticationSession ceremony = session.getAttribute(AuthenticationSession.ATTRIBUTE);
    if (ceremony != null) {
      if (!reference.equals(ceremony.reference())) {
        return Optional.empty();
      }
      if (PASSWORD_STATE.equals(ceremony.state())) {
        SecurityContext context =
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null) {
          return Optional.empty();
        }
        try {
          var identity = AuthenticationIdentity.from(context.getAuthentication());
          if (!identity.account().equals(ceremony.account())
              || identity.epoch() != ceremony.epoch()
              || context.getAuthentication().getAuthorities().stream()
                  .noneMatch(
                      authority ->
                          authority instanceof FactorGrantedAuthority factor
                              && FactorGrantedAuthority.PASSWORD_AUTHORITY.equals(
                                  factor.getAuthority())
                              && ceremony.createdAt().equals(factor.getIssuedAt()))) {
            return Optional.empty();
          }
        } catch (AuthenticationRequiredException exception) {
          return Optional.empty();
        }
      }
      return Optional.of(ceremony);
    }
    var completed = snapshot(session);
    if (completed == null || !reference.equals(completed.reference())) {
      return Optional.empty();
    }
    Instant verifiedAt = completed.expiresAt().minus(AuthenticationLifetime.FULL_ABSOLUTE);
    return Optional.of(
        new AuthenticationSession(
            reference,
            completed.account(),
            completed.epoch(),
            "full",
            verifiedAt,
            completed.expiresAt(),
            verifiedAt,
            verifiedAt,
            null,
            null,
            null,
            completed.target(),
            null,
            null));
  }

  /** Standard principal indexing also finds expired contexts for physical revocation. */
  public List<UUID> references(UUID account) {
    return repository.findByPrincipalName(account.toString()).values().stream()
        .map(session -> session.getAttribute("hortinis.authority"))
        .filter(SessionAuthorityReference.class::isInstance)
        .map(SessionAuthorityReference.class::cast)
        .map(SessionAuthorityReference::value)
        .sorted()
        .toList();
  }

  /** Supported native-row lock strengths; no request text is used as a SQL clause. */
  public enum Lock {
    NONE(""),
    SHARE(" FOR SHARE OF s"),
    UPDATE(" FOR UPDATE OF s");
    private final String sql;

    Lock(String sql) {
      this.sql = sql;
    }
  }

  private Snapshot snapshot(Session session) {
    SecurityContext context =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    if (context == null || !factors.authorize(context::getAuthentication, session).isGranted()) {
      return null;
    }
    try {
      var identity = AuthenticationIdentity.from(context.getAuthentication());
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
      if (expires.minus(AuthenticationLifetime.FULL_ABSOLUTE).isAfter(clock.instant())) {
        return null;
      }
      return new Snapshot(
          identity.account(),
          identity.epoch(),
          reference.value(),
          target,
          session.getCreationTime(),
          expires);
    } catch (AuthenticationRequiredException exception) {
      return null;
    }
  }

  /** Contains no native cookie identifier or credential material. */
  public record Snapshot(
      UUID account,
      long epoch,
      UUID reference,
      String target,
      Instant createdAt,
      Instant expiresAt) {
    @Override
    public String toString() {
      return "NativeSessionSnapshot[redacted]";
    }
  }
}
