package com.hortinis.sync;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSession;
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
  private static final String PASSWORD_STATE = "password";
  private final java.util.Map<UUID, String> verifiers = new java.util.HashMap<>();
  private final JdbcTemplate jdbc;
  private final org.springframework.session.FindByIndexNameSessionRepository<? extends Session>
      repository;

  NativeAuthenticationSessions(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    repository = AuthenticationJdbcSessions.create(jdbc, transactions);
  }

  static void installInspectionView(JdbcTemplate jdbc) {
    jdbc.execute(
        """
        CREATE OR REPLACE VIEW test_native_ceremony AS
        SELECT (c->>'reference')::uuid AS session_reference,
          encode(sha256(convert_to(s.session_id, 'UTF8')), 'hex') AS session_verifier,
          c->>'state' AS state, (c->>'account')::uuid AS account_id,
          nullif((c->>'epoch')::bigint, 0) AS authority_epoch,
          (c->>'createdAt')::timestamptz AS created_at,
          (c->>'expiresAt')::timestamptz AS expires_at,
          c->>'loginAt' IS NOT NULL AS step_up,
          (c->>'bothFactorsAt')::timestamptz AS both_factors_at,
          c->>'stagedLogin' AS staged_login_identifier,
          c->>'stagedPassword' AS staged_password_verifier,
          decode(c->>'stagedHandle', 'base64') AS staged_user_handle,
          c->>'logoutTarget' AS logout_target, c->>'recoveryKind' AS recovery_kind,
          decode(c->>'replacementCredential', 'base64') AS replacement_credential_id
        FROM spring_session s JOIN spring_session_attributes a ON a.session_primary_id = s.primary_id,
          LATERAL (SELECT substring(convert_from(a.attribute_bytes, 'UTF8') FROM 13)::jsonb AS c) d
        WHERE a.attribute_name = 'hortinis.ceremony'
        """);
  }

  UUID limited(UUID account, String state, Instant created) {
    UUID reference = UUID.randomUUID();
    var ceremony =
        new AuthenticationSession(
            reference,
            account,
            account == null ? 0 : 1,
            state,
            created,
            created.plusSeconds(300),
            null,
            null,
            null,
            null,
            null,
            AuthenticationSecrets.randomValue(),
            null,
            null);
    seedLimited(repository, ceremony);
    verifiers.put(reference, AuthenticationSecrets.digest(identifier(reference)));
    return reference;
  }

  private static <S extends Session> void seedLimited(
      SessionRepository<S> repository, AuthenticationSession ceremony) {
    S session = repository.createSession();
    session.setMaxInactiveInterval(java.time.Duration.ofMinutes(5));
    session.setAttribute("hortinis.authority", new SessionAuthorityReference(ceremony.reference()));
    stage(session, ceremony);
    repository.save(session);
  }

  private static void stage(Session session, AuthenticationSession ceremony) {
    session.setAttribute(AuthenticationSession.ATTRIBUTE, ceremony);
    if (ceremony.account() != null) {
      session.setAttribute(
          org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
          ceremony.account().toString());
    }
    if (PASSWORD_STATE.equals(ceremony.state())) {
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          AuthenticationIdentity.password(
              ceremony.account(), ceremony.epoch(), ceremony.createdAt()));
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
  }

  AuthenticationSession ceremony(UUID reference) {
    return repository.findById(identifier(reference)).getAttribute(AuthenticationSession.ATTRIBUTE);
  }

  void change(UUID reference, java.util.Map<String, Object> changes) {
    var mapper = new tools.jackson.databind.json.JsonMapper();
    tools.jackson.databind.node.ObjectNode node = mapper.valueToTree(ceremony(reference));
    changes.forEach((key, value) -> node.set(key, mapper.valueToTree(value)));
    var changed = mapper.treeToValue(node, AuthenticationSession.class);
    mutate(repository, identifier(reference), session -> stage(session, changed));
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
