package com.hortinis.sync;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAttributeCodec;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;

/** Test-only seeding and mutation of real native sessions; never included in the executable. */
final class NativeAuthenticationSessions {
  private final java.util.Map<UUID, String> verifiers = new java.util.HashMap<>();
  private final JdbcTemplate jdbc;
  private final org.springframework.session.FindByIndexNameSessionRepository<? extends Session>
      repository;

  NativeAuthenticationSessions(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    repository = AuthenticationJdbcSessions.create(jdbc, transactions);
  }

  AuthenticationNativeSessions persistence(Clock clock) {
    return new AuthenticationNativeSessions(repository, clock, JdbcClient.create(jdbc));
  }

  UUID full(UUID account, Instant verifiedAt) {
    UUID reference = UUID.randomUUID();
    seed(repository, account, verifiedAt, reference);
    verifiers.put(reference, AuthenticationSecrets.digest(identifier(reference)));
    return reference;
  }

  private static <S extends Session> void seed(
      SessionRepository<S> repository, UUID account, Instant verifiedAt, UUID reference) {
    S session = repository.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(AuthenticationIdentity.completed(account, 1, verifiedAt));
    session.setAttribute("hortinis.authority", new SessionAuthorityReference(reference));
    session.setAttribute(
        AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE,
        AuthenticationSecrets.randomValue());
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    repository.save(session);
  }

  String identifier(UUID reference) {
    return jdbc.queryForObject(
        "SELECT s.session_id FROM spring_session s "
            + "JOIN spring_session_attributes a ON a.session_primary_id = s.primary_id "
            + "WHERE a.attribute_name = 'hortinis.authority' AND a.attribute_bytes = ?",
        String.class,
        SessionAttributeCodec.conversionService()
            .convert(new SessionAuthorityReference(reference), byte[].class));
  }

  String verifier(UUID reference) {
    return verifiers.computeIfAbsent(
        reference, value -> AuthenticationSecrets.digest(identifier(value)));
  }

  void factors(UUID reference, Instant verifiedAt) {
    mutate(
        repository,
        identifier(reference),
        session -> {
          SecurityContext current =
              session.getAttribute(
                  HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
          var identity = AuthenticationIdentity.from(current.getAuthentication());
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              AuthenticationIdentity.completed(identity.account(), identity.epoch(), verifiedAt));
          session.setAttribute(
              HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        });
  }

  void clearAuthentication(UUID reference) {
    mutate(
        repository,
        identifier(reference),
        session ->
            session.removeAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
  }

  void delete(UUID reference) {
    repository.deleteById(identifier(reference));
  }

  void rotate(UUID reference, UUID replacement) {
    mutate(
        repository,
        identifier(reference),
        session -> {
          session.changeSessionId();
          session.setAttribute("hortinis.authority", new SessionAuthorityReference(replacement));
        });
  }

  private static <S extends Session> void mutate(
      SessionRepository<S> repository, String identifier, Consumer<S> mutation) {
    S session = java.util.Objects.requireNonNull(repository.findById(identifier));
    mutation.accept(session);
    repository.save(session);
  }
}
