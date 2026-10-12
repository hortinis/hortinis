package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fresh-storage invariants only; runtime authority and credential verification have separate tests.
 */
@Testcontainers
class AuthenticationSchemaIntegrationTest {
  private static final java.time.Instant SYNTHETIC_LOGIN =
      java.time.Instant.parse("2026-10-11T12:00:00Z");
  private static final String SYNTHETIC_VERIFIER =
      "{argon2id}$argon2id$v=19$m=65536,t=3,p=1$synthetic-not-a-credential";
  private static final String DISABLE_CHANGE = "disable";
  private static final String EPOCH_CHANGE = "epoch";
  private static final String MODE_CHANGE = "mode";
  private static final String CHALLENGE_COUNT = "SELECT count(*) FROM authentication_challenge";
  private static final String SESSION_DELETE_CHANGE = "session-delete";
  private static final String EXPIRY_CHANGE = "expiry";
  private static final String FUTURE_CHANGE = "future";
  private static final String SWITCH_NO_AUTH =
      "UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'";
  private static final String ADVANCE_EPOCH =
      "UPDATE authentication_account SET authority_epoch = 2";
  private static final String RECOVERY_CODE_COUNT =
      "SELECT count(*) FROM authentication_recovery_code";
  private static final String SWITCH_SINGLE_AUTH =
      "UPDATE technical_server_installation SET access_mode = 'single-user-auth'";
  private static final String SESSION_AUTHORITY_COUNT =
      "SELECT count(*) FROM authentication_session_authority";
  private static final String SESSION_STATE_UPDATE_PREFIX =
      "UPDATE authentication_session_authority SET state ";
  private static final String SESSION_UPDATE_PREFIX =
      "UPDATE authentication_session_authority SET ";
  private static final String ACCOUNT_EPOCH_UPDATE_PREFIX =
      "UPDATE authentication_account SET authority_epoch ";
  private static final String SCOPE_WHERE = "technical_synchronization_scope WHERE ";
  private static final String SESSION_INSERT_PREFIX =
      "INSERT INTO authentication_session_authority ";
  private static final String SESSION_COLUMNS_PREFIX = "(session_verifier, logout_target, state, ";
  private static final String SESSION_ATTRIBUTE_COUNT =
      "SELECT count(*) FROM spring_session_attributes";
  private static final String AUTHORITY_ATTRIBUTE = "hortinis.authority";
  private static final String CSRF_ATTRIBUTE = "csrf";
  private static final String SESSION_REFERENCE_WHERE = "WHERE session_reference = ?";
  private static final String CANONICAL_RANDOM_FIXTURE = "A".repeat(43);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  private JdbcTemplate jdbc;

  @BeforeEach
  void freshSchema() {
    String schema = "authentication_test_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
  }

  @Test
  void sessionLayoutAcceptsOnlySelectedOpaqueIdsAndNoStablePrincipalName() {
    assertThat(
            jdbc.queryForObject(
                "SELECT character_maximum_length FROM "
                    + "information_schema.columns WHERE table_schema = "
                    + "current_schema() AND table_name = "
                    + "'spring_session' AND column_name = 'session_id'",
                Integer.class))
        .isEqualTo(43);
    String row = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO spring_session (primary_id, "
            + "session_id, creation_time, last_access_time, "
            + "max_inactive_interval, expiry_time) VALUES (?, ?, "
            + "0, 0, 300, 300000)",
        row,
        CANONICAL_RANDOM_FIXTURE);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE spring_session SET session_id = ? WHERE primary_id = ?",
                    UUID.randomUUID().toString(),
                    row))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE spring_session SET principal_name = "
                        + "'synthetic-login' WHERE primary_id = ?",
                    row))
        .isInstanceOf(RuntimeException.class);
    jdbc.update(
        "INSERT INTO spring_session_attributes VALUES (?, 'synthetic-test-attribute', ?)",
        row,
        new byte[] {1});
    jdbc.update("DELETE FROM spring_session WHERE primary_id = ?", row);
    assertThat(jdbc.queryForObject(SESSION_ATTRIBUTE_COUNT, Integer.class)).isZero();
  }

  @Test
  void ownershipAndCredentialParametersAreExplicitAndConstrained() {
    UUID account = account();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_account SET "
                        + "login_identifier = 'GardenER' WHERE account_id = ?",
                    account))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_account SET "
                        + "password_verifier = '{noop}synthetic' WHERE "
                        + "account_id = ?",
                    account))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_account SET authority_epoch = 0 WHERE account_id = ?",
                    account))
        .isInstanceOf(RuntimeException.class);
    jdbc.update(
        "INSERT INTO authentication_credential "
            + "(credential_id, account_id, public_key_cose, "
            + "algorithm, signature_count, backup_eligible, "
            + "backed_up) VALUES (?, ?, ?, -7, 0, FALSE, FALSE)",
        new byte[] {1},
        account,
        new byte[] {2});
    assertThatThrownBy(() -> jdbc.update("UPDATE authentication_credential SET algorithm = -999"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE authentication_credential SET backed_up = TRUE"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE authentication_credential SET signature_count = 4294967296"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_account SET "
                        + "synchronization_scope_id = ? WHERE account_id = ?",
                    UUID.randomUUID(),
                    account))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void limitedAuthorityCannotBecomeFullWithoutCompleteTimesAndAccount() {
    UUID session = anonymous();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    SESSION_STATE_UPDATE_PREFIX + "= 'full' WHERE session_reference = ?", session))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    SESSION_UPDATE_PREFIX
                        + "expires_at = created_at + INTERVAL '301 seconds' "
                        + SESSION_REFERENCE_WHERE,
                    session))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    SESSION_STATE_UPDATE_PREFIX + "= 'setup' WHERE session_reference = ?", session))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    SESSION_UPDATE_PREFIX + "logout_target = ? WHERE session_reference = ?",
                    "A".repeat(42) + "B",
                    session))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void challengeAndRecoveryVerifiersHaveOneConcurrentConsumerAndCascadeOnDeletion()
      throws Exception {
    UUID account = account();
    UUID session = anonymous();
    jdbc.update(
        "INSERT INTO authentication_challenge VALUES (?, "
            + "?, 'login', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
            + "+ INTERVAL '5 minutes', NULL)",
        session,
        new byte[32]);
    assertSingleConsumer(
        "DELETE FROM authentication_challenge WHERE "
            + "session_reference = ? RETURNING session_reference",
        session);
    jdbc.update(
        "INSERT INTO authentication_recovery_code VALUES "
            + "(?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + "
            + "INTERVAL '365 days')",
        account,
        "a".repeat(64));
    assertSingleConsumer(
        "DELETE FROM authentication_recovery_code WHERE account_id = ? RETURNING account_id",
        account);
    jdbc.update(
        "INSERT INTO authentication_credential "
            + "(credential_id, account_id, public_key_cose, "
            + "algorithm, signature_count, backup_eligible, "
            + "backed_up) VALUES (?, ?, ?, -7, 0, FALSE, FALSE)",
        new byte[] {1},
        account,
        new byte[] {2});
    jdbc.update("DELETE FROM authentication_account WHERE account_id = ?", account);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authentication_credential", Integer.class))
        .isZero();
  }

  @Test
  void expiredLimitedSessionCannotReleaseItsClaimForReuseAndSecurityEventsRejectPayloads() {
    UUID session = anonymous();
    jdbc.update(
        "INSERT INTO authentication_operator_ticket "
            + "(secret_verifier, purpose, target_id, "
            + "server_instance_id, access_mode, created_at, "
            + "expires_at, claimed_session_reference) SELECT ?, "
            + "'setup', s.synchronization_scope_id, "
            + "s.server_instance_id, 'single-user-auth', "
            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL "
            + "'10 minutes', ? FROM "
            + "technical_synchronization_scope s WHERE "
            + "technical_default",
        "a".repeat(64),
        session);
    jdbc.update(
        "DELETE FROM authentication_session_authority WHERE session_reference = ?", session);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_operator_ticket", Integer.class))
        .isZero();
    jdbc.update(
        "INSERT INTO authentication_security_event VALUES "
            + "(?, CURRENT_TIMESTAMP, 'login', 'denied', "
            + "'invalid-proof')",
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE authentication_security_event SET reason = 'synthetic-private-detail'"))
        .isInstanceOf(RuntimeException.class);
    List<String> fields =
        jdbc.query(
            "SELECT column_name FROM "
                + "information_schema.columns WHERE table_schema = "
                + "current_schema() AND table_name = "
                + "'authentication_security_event'",
            (row, number) -> row.getString(1));
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "request_reference", "occurred_at", "event", "outcome", "reason");
  }

  @Test
  void actualJdbcSessionRoundTripsClosedAttributesAndIndependentOpaqueId() {
    var transactionManager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource()));
    var repository =
        com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions.create(
            jdbc, transactionManager);
    verifySessionRoundTrip(repository);
  }

  private <S extends org.springframework.session.Session> void verifySessionRoundTrip(
      org.springframework.session.SessionRepository<S> repository) {
    S session = repository.createSession();
    var reference =
        new com.hortinis.sync.authentication.SessionAuthorityReference(UUID.randomUUID());
    session.setAttribute(AUTHORITY_ATTRIBUTE, reference);
    session.setAttribute(
        CSRF_ATTRIBUTE,
        new org.springframework.security.web.csrf.DefaultCsrfToken(
            "X-CSRF-TOKEN",
            "_csrf",
            com.hortinis.sync.authentication.AuthenticationSecrets.randomValue()));
    repository.save(session);
    assertThat(
            com.hortinis.sync.authentication.AuthenticationSecrets.isRandomValue(session.getId()))
        .isTrue();
    S reloaded = java.util.Objects.requireNonNull(repository.findById(session.getId()));
    assertThat((Object) reloaded.getAttribute(AUTHORITY_ATTRIBUTE)).isEqualTo(reference);
    assertThat((Object) reloaded.getAttribute(CSRF_ATTRIBUTE))
        .isInstanceOf(org.springframework.security.web.csrf.CsrfToken.class);
    String previous = reloaded.getId();
    reloaded.changeSessionId();
    repository.save(reloaded);
    assertThat(reloaded.getId()).isNotEqualTo(previous);
    assertThat(repository.findById(previous)).isNull();
    assertThat(repository.findById(reloaded.getId())).isNotNull();
    repository.deleteById(reloaded.getId());
    assertThat(jdbc.queryForObject(SESSION_ATTRIBUTE_COUNT, Integer.class)).isZero();
  }

  @Test
  void jdbcAdmissionAndImmediateAttributesCommitWithAuthority() {
    verifyAtomicSessionAdmission(sessionRepository(), false);
  }

  @Test
  void jdbcAdmissionAndImmediateAttributesRollBackWithAuthority() {
    verifyAtomicSessionAdmission(sessionRepository(), true);
  }

  private org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository() {
    return com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions.create(
        jdbc,
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource())));
  }

  private <S extends org.springframework.session.Session> void verifyAtomicSessionAdmission(
      org.springframework.session.SessionRepository<S> repository, boolean rollBack) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    String sessionId =
        transaction()
            .execute(
                status -> {
                  S session = repository.createSession();
                  var reference = admissionAt(SYNTHETIC_LOGIN).admitAnonymous(session.getId());
                  session.setMaxInactiveInterval(java.time.Duration.ofMinutes(5));
                  session.setAttribute(AUTHORITY_ATTRIBUTE, reference);
                  session.setAttribute(CSRF_ATTRIBUTE, syntheticCsrf());
                  assertThat(transportCount()).isEqualTo(1);
                  assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class))
                      .isEqualTo(1);
                  assertThat(jdbc.queryForObject(SESSION_ATTRIBUTE_COUNT, Integer.class))
                      .isEqualTo(2);
                  if (rollBack) {
                    status.setRollbackOnly();
                  }
                  return session.getId();
                });
    assertThat(transportCount()).isEqualTo(rollBack ? 0 : 1);
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class))
        .isEqualTo(rollBack ? 0 : 1);
    if (rollBack) {
      assertThat(repository.findById(sessionId)).isNull();
    } else {
      S reloaded = java.util.Objects.requireNonNull(repository.findById(sessionId));
      assertThat(reloaded.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofMinutes(5));
      assertThat((Object) reloaded.getAttribute(AUTHORITY_ATTRIBUTE))
          .isInstanceOf(com.hortinis.sync.authentication.SessionAuthorityReference.class);
      assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session", String.class))
          .isNull();
    }
  }

  @Test
  void jdbcRotationAndAuthorityVerifierCommitTogether() {
    verifyAtomicSessionRotation(sessionRepository(), false);
  }

  @Test
  void jdbcRotationAndAuthorityVerifierRollBackTogether() {
    verifyAtomicSessionRotation(sessionRepository(), true);
  }

  private <S extends org.springframework.session.Session> void verifyAtomicSessionRotation(
      org.springframework.session.SessionRepository<S> repository, boolean rollBack) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    S session =
        transaction()
            .execute(
                status -> {
                  S created = repository.createSession();
                  var reference = admissionAt(SYNTHETIC_LOGIN).admitAnonymous(created.getId());
                  created.setAttribute(AUTHORITY_ATTRIBUTE, reference);
                  created.setAttribute(CSRF_ATTRIBUTE, syntheticCsrf());
                  return created;
                });
    java.util.Objects.requireNonNull(session);
    String oldId = session.getId();
    com.hortinis.sync.authentication.SessionAuthorityReference oldReference =
        session.getAttribute(AUTHORITY_ATTRIBUTE);
    var newReference =
        new com.hortinis.sync.authentication.SessionAuthorityReference(UUID.randomUUID());
    org.springframework.security.web.csrf.CsrfToken oldCsrf = session.getAttribute(CSRF_ATTRIBUTE);
    transaction()
        .executeWithoutResult(
            status -> {
              final String newId = session.changeSessionId();
              session.setAttribute(AUTHORITY_ATTRIBUTE, newReference);
              session.setAttribute(CSRF_ATTRIBUTE, syntheticCsrf());
              repository.save(session);
              jdbc.update(
                  SESSION_UPDATE_PREFIX
                      + "session_reference = ?, session_verifier = ? "
                      + SESSION_REFERENCE_WHERE,
                  newReference.value(),
                  com.hortinis.sync.authentication.AuthenticationSecrets.digest(newId),
                  oldReference.value());
              assertThat(repository.findById(oldId)).isNull();
              assertThat(repository.findById(newId)).isNotNull();
              if (rollBack) {
                status.setRollbackOnly();
              }
            });
    String newId = session.getId();
    assertThat(newId).isNotEqualTo(oldId);
    S persisted = java.util.Objects.requireNonNull(repository.findById(rollBack ? oldId : newId));
    org.springframework.security.web.csrf.CsrfToken persistedCsrf =
        persisted.getAttribute(CSRF_ATTRIBUTE);
    assertThat(persistedCsrf.getToken().equals(oldCsrf.getToken())).isEqualTo(rollBack);
    assertThat((Object) persisted.getAttribute(AUTHORITY_ATTRIBUTE))
        .isEqualTo(rollBack ? oldReference : newReference);
    assertThat(
            jdbc.queryForObject(
                "SELECT session_reference FROM authentication_session_authority", UUID.class))
        .isEqualTo(rollBack ? oldReference.value() : newReference.value());
    assertThat(repository.findById(rollBack ? newId : oldId)).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT session_verifier FROM authentication_session_authority", String.class))
        .isEqualTo(
            com.hortinis.sync.authentication.AuthenticationSecrets.digest(
                rollBack ? oldId : newId));
  }

  @Test
  void jdbcDeletionRollsBackAndLaterSaveCannotResurrectRevokedAuthority() {
    verifyAtomicSessionDeletion(sessionRepository());
  }

  private <S extends org.springframework.session.Session> void verifyAtomicSessionDeletion(
      org.springframework.session.SessionRepository<S> repository) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    S session =
        transaction()
            .execute(
                status -> {
                  S created = repository.createSession();
                  created.setAttribute(
                      AUTHORITY_ATTRIBUTE,
                      admissionAt(SYNTHETIC_LOGIN).admitAnonymous(created.getId()));
                  created.setAttribute(CSRF_ATTRIBUTE, syntheticCsrf());
                  return created;
                });
    java.util.Objects.requireNonNull(session);
    transaction()
        .executeWithoutResult(
            status -> {
              repository.deleteById(session.getId());
              jdbc.update("DELETE FROM authentication_session_authority");
              assertThat(transportCount()).isZero();
              assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isZero();
              status.setRollbackOnly();
            });
    assertThat(repository.findById(session.getId())).isNotNull();
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isEqualTo(1);
    transaction()
        .executeWithoutResult(
            status -> {
              repository.deleteById(session.getId());
              jdbc.update("DELETE FROM authentication_session_authority");
            });
    session.changeSessionId();
    session.setLastAccessedTime(java.time.Instant.now());
    repository.save(session);
    assertThat(transportCount()).isZero();
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isZero();
    assertThat(repository.findById(session.getId())).isNull();
    // Spring ignores missing-parent attribute inserts after a concurrent transport deletion.
    session.setAttribute(
        "new-authority",
        new com.hortinis.sync.authentication.SessionAuthorityReference(UUID.randomUUID()));
    assertThat(transportCount()).isZero();
    assertThat(jdbc.queryForObject(SESSION_ATTRIBUTE_COUNT, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isZero();
  }

  @Test
  void jdbcUnsupportedPrincipalAttributeRollsBackAdmission() {
    verifyRejectedSessionAttribute(sessionRepository());
  }

  private <S extends org.springframework.session.Session> void verifyRejectedSessionAttribute(
      org.springframework.session.SessionRepository<S> repository) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          S session = repository.createSession();
                          session.setAttribute(
                              AUTHORITY_ATTRIBUTE,
                              admissionAt(SYNTHETIC_LOGIN).admitAnonymous(session.getId()));
                          session.setAttribute(
                              "SPRING_SECURITY_CONTEXT", "unsupported-synthetic-principal");
                        }))
        .isInstanceOf(org.springframework.core.convert.ConversionFailedException.class);
    assertThat(transportCount()).isZero();
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isZero();
  }

  private int transportCount() {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
  }

  private static org.springframework.security.web.csrf.CsrfToken syntheticCsrf() {
    return new org.springframework.security.web.csrf.DefaultCsrfToken(
        "X-CSRF-TOKEN",
        "_csrf",
        com.hortinis.sync.authentication.AuthenticationSecrets.randomValue());
  }

  @Test
  void currentAuthorityChecksExactExpiryAndRejectsLookupOutsideProtectedTransaction() {
    UUID account = account();
    java.time.Instant login = SYNTHETIC_LOGIN;
    UUID session = fullSession(account, login);
    var authority = authorityAt(login);
    var observed = authority.findFull("b".repeat(64), session).orElseThrow();
    assertThat(observed.account()).isEqualTo(account);
    assertThat(observed.toString()).doesNotContain(account.toString(), session.toString());
    assertThatThrownBy(() -> requireAuthority(authority, observed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Authentication authority requires a protected transaction.");
    transaction().executeWithoutResult(status -> requireAuthority(authority, observed));
    assertThat(authorityAt(login.plusSeconds(1799)).findFull("b".repeat(64), session)).isPresent();
    assertThat(authorityAt(login.plusSeconds(1800)).findFull("b".repeat(64), session)).isEmpty();
    jdbc.update(
        SESSION_UPDATE_PREFIX + "last_interaction_at = ? WHERE session_reference = " + "?",
        java.sql.Timestamp.from(login.plusSeconds(43199)),
        session);
    assertThat(authorityAt(login.plusSeconds(43199)).findFull("b".repeat(64), session)).isPresent();
    assertThat(authorityAt(login.plusSeconds(43200)).findFull("b".repeat(64), session)).isEmpty();
    assertThat(authority.findFull("invalid", session)).isEmpty();
    assertThat(authority.findFull("b".repeat(64), UUID.randomUUID())).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"created_at", "both_factors_at"})
  void currentProtectedAuthorityRejectsFutureCreationOrFactorTimes(String column) {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var authority = authorityAt(SYNTHETIC_LOGIN);
    var retained = authority.findFull("b".repeat(64), session).orElseThrow();
    String selectedColumn = "created_at".equals(column) ? "created_at" : "both_factors_at";
    jdbc.update(
        SESSION_UPDATE_PREFIX + selectedColumn + " = ? WHERE session_reference = ?",
        java.sql.Timestamp.from(SYNTHETIC_LOGIN.plusSeconds(1)),
        session);
    assertThat(authority.findFull("b".repeat(64), session)).isEmpty();
    assertThatThrownBy(
            () ->
                transaction().executeWithoutResult(status -> requireAuthority(authority, retained)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class);
  }

  @Test
  void newRotationReferenceDeniesPreviouslyResolvedRequestWithSameAccountAndEpoch() {
    UUID account = account();
    UUID oldReference = fullSession(account, SYNTHETIC_LOGIN);
    var authority = authorityAt(SYNTHETIC_LOGIN);
    var oldObservation = authority.findFull("b".repeat(64), oldReference).orElseThrow();
    UUID newReference = UUID.randomUUID();
    String newVerifier =
        com.hortinis.sync.authentication.AuthenticationSecrets.digest(
            com.hortinis.sync.authentication.AuthenticationSecrets.randomValue());
    transaction()
        .executeWithoutResult(
            status ->
                jdbc.update(
                    SESSION_UPDATE_PREFIX
                        + "session_reference = ?, session_verifier = ? "
                        + SESSION_REFERENCE_WHERE,
                    newReference,
                    newVerifier,
                    oldReference));
    var newObservation = authority.findFull(newVerifier, newReference).orElseThrow();
    assertThat(newObservation.account()).isEqualTo(oldObservation.account());
    assertThat(newObservation.epoch()).isEqualTo(oldObservation.epoch());
    transaction().executeWithoutResult(status -> requireAuthority(authority, newObservation));
    assertThat(authority.findFull("b".repeat(64), oldReference)).isEmpty();
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(status -> requireAuthority(authority, oldObservation)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {DISABLE_CHANGE, EPOCH_CHANGE, SESSION_DELETE_CHANGE, "partial", MODE_CHANGE})
  void retainedMetadataCannotAuthorizeAfterCurrentStateChanges(String change) {
    UUID account = account();
    java.time.Instant login = SYNTHETIC_LOGIN;
    UUID session = fullSession(account, login);
    var authority = authorityAt(login);
    var observed = authority.findFull("b".repeat(64), session).orElseThrow();
    switch (change) {
      case DISABLE_CHANGE ->
          jdbc.update(
              "UPDATE authentication_account SET status = 'disabled' WHERE account_id = ?",
              account);
      case EPOCH_CHANGE ->
          jdbc.update(
              ACCOUNT_EPOCH_UPDATE_PREFIX + "= authority_epoch + 1 WHERE account_id = ?", account);
      case SESSION_DELETE_CHANGE ->
          jdbc.update(
              "DELETE FROM authentication_session_authority WHERE session_reference = ?", session);
      case "partial" ->
          jdbc.update(
              SESSION_STATE_UPDATE_PREFIX
                  + "= 'password', expires_at = created_at + INTERVAL "
                  + "'5 minutes' WHERE session_reference = ?",
              session);
      case MODE_CHANGE -> jdbc.update(SWITCH_NO_AUTH);
      default -> throw new AssertionError("Unknown synthetic state change.");
    }
    assertThat(authority.findFull("b".repeat(64), session)).isEmpty();
    assertThatThrownBy(
            () ->
                transaction().executeWithoutResult(status -> requireAuthority(authority, observed)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class)
        .hasMessage("Authentication is required.");
  }

  @Test
  void authorityLocksSerializeRevocationWithContentAndDenyTheRetainedContextAfterCommit()
      throws Exception {
    UUID account = account();
    java.time.Instant login = SYNTHETIC_LOGIN;
    UUID session = fullSession(account, login);
    var authority = authorityAt(login);
    var observed = authority.findFull("b".repeat(64), session).orElseThrow();
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var revokePid = new java.util.concurrent.atomic.AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var content =
          executor.submit(
              () ->
                  transaction()
                      .executeWithoutResult(
                          status -> {
                            requireAuthority(authority, observed);
                            jdbc.queryForObject(
                                "SELECT synchronization_scope_id FROM "
                                    + SCOPE_WHERE
                                    + "synchronization_scope_id = ? FOR UPDATE",
                                UUID.class,
                                observed.scope());
                            locked.countDown();
                            await(release);
                            jdbc.update(
                                "UPDATE technical_synchronization_scope SET "
                                    + "last_published_sequence = last_published_sequence "
                                    + "+ 1 WHERE synchronization_scope_id = ?",
                                observed.scope());
                          }));
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        var revoke =
            executor.submit(
                () ->
                    transaction()
                        .executeWithoutResult(
                            status -> {
                              revokePid.set(
                                  java.util.Objects.requireNonNull(
                                      jdbc.queryForObject(
                                          "SELECT pg_backend_pid()", Integer.class)));
                              jdbc.queryForObject(
                                  "SELECT account_id FROM authentication_account "
                                      + "WHERE account_id = ? FOR UPDATE",
                                  UUID.class,
                                  account);
                              jdbc.update(
                                  ACCOUNT_EPOCH_UPDATE_PREFIX
                                      + "= authority_epoch + 1 WHERE account_id = ?",
                                  account);
                              jdbc.update(
                                  "DELETE FROM authentication_session_authority "
                                      + SESSION_REFERENCE_WHERE,
                                  session);
                            }));
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (System.nanoTime() < limit && !waiting) {
          waiting =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                          + "WHERE pid = ? AND wait_event_type = 'Lock')",
                      Boolean.class,
                      revokePid.get()));
          if (!waiting) {
            Thread.sleep(10);
          }
        }
        assertThat(waiting).isTrue();
        assertThat(revoke.isDone()).isFalse();
        release.countDown();
        content.get(10, TimeUnit.SECONDS);
        revoke.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertThatThrownBy(
            () ->
                transaction().executeWithoutResult(status -> requireAuthority(authority, observed)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_published_sequence FROM "
                    + SCOPE_WHERE
                    + "synchronization_scope_id = ?",
                Long.class,
                observed.scope()))
        .isEqualTo(1);
  }

  @Test
  void scopedPersistenceRechecksCurrentAuthorityBeforeCounterLookupOrPublication() {
    UUID account = account();
    java.time.Instant login = SYNTHETIC_LOGIN;
    UUID session = fullSession(account, login);
    var authority = authorityAt(login);
    var observed = authority.findFull("b".repeat(64), session).orElseThrow();
    var scopes =
        new com.hortinis.sync.persistence.SynchronizationScopePersistence(
            org.springframework.jdbc.core.simple.JdbcClient.create(jdbc), authority);
    var context =
        com.hortinis.sync.access.SyntheticAccessContexts.authenticatedSession(
            new com.hortinis.sync.access.ServerInstanceId(observed.instance().toString()),
            new com.hortinis.sync.access.SynchronizationScopeId(observed.scope().toString()),
            com.hortinis.sync.access.ServerAccessMode.SINGLE_USER_AUTH,
            new com.hortinis.sync.access.AccountId(account.toString()),
            session,
            observed.epoch());
    transaction()
        .executeWithoutResult(
            status -> {
              scopes.lockForAcceptance(context);
              assertThat(scopes.requireScope(context)).isZero();
              assertThat(scopes.allocateSequence(context)).isEqualTo(1);
            });
    jdbc.update(
        ACCOUNT_EPOCH_UPDATE_PREFIX + "= authority_epoch + 1 WHERE account_id = ?", account);
    assertThatThrownBy(
            () -> transaction().executeWithoutResult(status -> scopes.requireScope(context)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class);
    assertThatThrownBy(
            () -> transaction().executeWithoutResult(status -> scopes.allocateSequence(context)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationRequiredException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_published_sequence FROM "
                    + SCOPE_WHERE
                    + "synchronization_scope_id = ?",
                Long.class,
                observed.scope()))
        .isEqualTo(1);
  }

  @Test
  void challengeReplacementAndCommittedConsumptionSurviveLaterPublicationRollback() {
    UUID account = account();
    UUID parent = passwordSession(account);
    var challenges = challengesAt(SYNTHETIC_LOGIN);
    byte[] first = new byte[32];
    byte[] second = new byte[32];
    second[0] = 7;
    issueChallenge(challenges, parent, first);
    issueChallenge(challenges, parent, second);
    assertThat(jdbc.queryForObject(CHALLENGE_COUNT, Integer.class)).isEqualTo(1);
    var receipt =
        challenges.consume(
            parent, com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN);
    assertThat(receipt.account()).isEqualTo(account);
    assertThat(receipt.epoch()).isEqualTo(1);
    assertThat(receipt.challenge()).isEqualTo(second);
    second[0] = 1;
    byte[] copy = receipt.challenge();
    copy[0] = 2;
    assertThat(receipt.challenge()[0]).isEqualTo((byte) 7);
    assertThat(receipt.toString()).doesNotContain(account.toString(), parent.toString());
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          jdbc.update(
                              ACCOUNT_EPOCH_UPDATE_PREFIX + "= 2 WHERE account_id = ?", account);
                          throw new com.hortinis.sync.authentication
                              .AuthenticationFailedException();
                        }))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThat(
            jdbc.queryForObject("SELECT authority_epoch FROM authentication_account", Long.class))
        .isEqualTo(1);
    assertChallengeSpent(challenges, parent);
  }

  @Test
  void rejectsNestedConsumptionBeforeDeletingAndAllowsExactlyOneConcurrentConsumer()
      throws Exception {
    UUID parent = passwordSession(account());
    var challenges = challengesAt(SYNTHETIC_LOGIN);
    issueChallenge(challenges, parent, new byte[32]);
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status ->
                            challenges.consume(
                                parent,
                                com.hortinis.sync.authentication.AuthenticationChallengePurpose
                                    .LOGIN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Consume the challenge before a publication transaction.");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> tryConsume(challenges, parent));
      var second = executor.submit(() -> tryConsume(challenges, parent));
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertChallengeSpent(challenges, parent);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        EXPIRY_CHANGE,
        FUTURE_CHANGE,
        "wrong-purpose",
        DISABLE_CHANGE,
        EPOCH_CHANGE,
        MODE_CHANGE,
        "state"
      })
  void deniedChallengeConsumptionCommitsDeletion(String change) {
    UUID account = account();
    UUID parent = passwordSession(account);
    var challenges = challengesAt(SYNTHETIC_LOGIN);
    issueChallenge(challenges, parent, new byte[32]);
    java.time.Instant now = SYNTHETIC_LOGIN;
    var purpose = com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN;
    switch (change) {
      case EXPIRY_CHANGE -> now = now.plusSeconds(300);
      case FUTURE_CHANGE -> now = now.minusSeconds(1);
      case "wrong-purpose" ->
          purpose = com.hortinis.sync.authentication.AuthenticationChallengePurpose.STEP_UP;
      case DISABLE_CHANGE -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case EPOCH_CHANGE -> jdbc.update(ADVANCE_EPOCH);
      case MODE_CHANGE -> jdbc.update(SWITCH_NO_AUTH);
      case "state" ->
          jdbc.update("UPDATE authentication_session_authority SET state = 'anonymous'");
      default -> throw new IllegalArgumentException("Unknown fixture change.");
    }
    var finalPurpose = purpose;
    var consuming = challengesAt(now);
    assertThatThrownBy(() -> consuming.consume(parent, finalPurpose))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThat(jdbc.queryForObject(CHALLENGE_COUNT, Integer.class)).isZero();
  }

  @Test
  void concurrentIssuanceCannotExceedAccountCapacityAndReplacementDoesNotSpendAnotherSlot()
      throws Exception {
    UUID account = account();
    var challenges = challengesAt(SYNTHETIC_LOGIN);
    UUID replacing = null;
    for (int index = 0; index < 7; index++) {
      UUID parent = passwordSession(account);
      issueChallenge(challenges, parent, new byte[32]);
      replacing = parent;
    }
    UUID firstParent = passwordSession(account);
    UUID secondParent = passwordSession(account);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> tryIssue(challenges, firstParent));
      var second = executor.submit(() -> tryIssue(challenges, secondParent));
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    issueChallenge(challenges, replacing, new byte[32]);
    assertThat(jdbc.queryForObject(CHALLENGE_COUNT, Integer.class)).isEqualTo(8);
    var expired = challengesAt(SYNTHETIC_LOGIN.plusSeconds(300));
    UUID renewed = passwordSession(account, SYNTHETIC_LOGIN.plusSeconds(300));
    issueChallenge(expired, renewed, new byte[32]);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authentication_challenge WHERE expires_at > ?",
                Integer.class,
                java.sql.Timestamp.from(SYNTHETIC_LOGIN.plusSeconds(300))))
        .isEqualTo(1);
  }

  @Test
  void credentialEnrollmentRequiresRecentCompleteFactorsAndExpiresAtTheParentDeadline() {
    UUID parent = fullSession(account(), SYNTHETIC_LOGIN);
    var purpose = com.hortinis.sync.authentication.AuthenticationChallengePurpose.CREDENTIAL;
    var recent = challengesAt(SYNTHETIC_LOGIN.plusSeconds(299));
    transaction().executeWithoutResult(status -> recent.issue(parent, purpose, new byte[32], null));
    var stale = challengesAt(SYNTHETIC_LOGIN.plusSeconds(300));
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> stale.issue(parent, purpose, new byte[32], null)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThatThrownBy(() -> stale.consume(parent, purpose))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    UUID partial =
        passwordSession(
            jdbc.queryForObject("SELECT account_id FROM authentication_account", UUID.class));
    var nearingExpiry = challengesAt(SYNTHETIC_LOGIN.plusSeconds(290));
    java.time.Instant expires =
        transaction()
            .execute(
                status ->
                    nearingExpiry.issue(
                        partial,
                        com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN,
                        new byte[32],
                        null));
    assertThat(expires).isEqualTo(SYNTHETIC_LOGIN.plusSeconds(300));
  }

  @Test
  void challengeIssuanceRequiresItsCallerTransactionAndClosedPurposeState() {
    UUID parent = passwordSession(account());
    var challenges = challengesAt(SYNTHETIC_LOGIN);
    assertThatThrownBy(
            () ->
                challenges.issue(
                    parent,
                    com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN,
                    new byte[32],
                    null))
        .isInstanceOf(IllegalStateException.class);
    for (var purpose : com.hortinis.sync.authentication.AuthenticationChallengePurpose.values()) {
      if (purpose != com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN) {
        assertThatThrownBy(
                () ->
                    transaction()
                        .executeWithoutResult(
                            status ->
                                challenges.issue(
                                    parent,
                                    purpose,
                                    new byte[32],
                                    purpose
                                            == com.hortinis.sync.authentication
                                                .AuthenticationChallengePurpose.RECOVER_PASSWORD
                                        ? "c".repeat(64)
                                        : null)))
            .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
      }
    }
    assertThat(jdbc.queryForObject(CHALLENGE_COUNT, Integer.class)).isZero();
  }

  @Test
  void recoveryCodeLifetimeUsesFixedDurationAcrossDatabaseDaylightSavingChanges() {
    UUID account = account();
    java.time.Instant created = java.time.Instant.parse("2026-03-10T12:00:00Z");
    java.time.Instant limit = created.plus(java.time.Duration.ofDays(365));
    transaction()
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL TIME ZONE 'America/New_York'");
              jdbc.update(
                  "INSERT INTO authentication_recovery_code VALUES (?, ?, ?, ?)",
                  account,
                  "c".repeat(64),
                  java.sql.Timestamp.from(created),
                  java.sql.Timestamp.from(limit));
            });
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          jdbc.execute("SET LOCAL TIME ZONE 'America/New_York'");
                          jdbc.update(
                              "UPDATE authentication_recovery_code SET expires_at = ?",
                              java.sql.Timestamp.from(limit.plusSeconds(1)));
                        }))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void codeReplacementReturnsTenIndependentValuesAndAtomicallyInvalidatesTheOldSet() {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var current = authorityAt(SYNTHETIC_LOGIN);
    var observed = current.findFull("b".repeat(64), session).orElseThrow();
    var recovery = recoveryAt(SYNTHETIC_LOGIN);
    var first = transaction().execute(status -> recovery.regenerate(observed));
    assertThat(first).isNotNull();
    assertThat(first.codes())
        .hasSize(10)
        .doesNotHaveDuplicates()
        .allMatch(com.hortinis.sync.authentication.AuthenticationSecrets::isRandomValue);
    assertThat(first.expiresAt()).isEqualTo(SYNTHETIC_LOGIN.plus(java.time.Duration.ofDays(365)));
    assertThat(first.toString()).doesNotContain(first.codes().getFirst());
    List<String> stored =
        jdbc.queryForList("SELECT secret_verifier FROM authentication_recovery_code", String.class);
    assertThat(stored).hasSize(10).doesNotContainAnyElementsOf(first.codes());
    var second = transaction().execute(status -> recovery.regenerate(observed));
    assertThat(second).isNotNull();
    assertThat(second.codes()).doesNotContainAnyElementsOf(first.codes());
    assertThat(
            transaction()
                .<Boolean>execute(status -> recovery.consume(account, 1, first.codes().getFirst())))
        .isFalse();
    assertThat(
            transaction()
                .<Boolean>execute(
                    status -> recovery.consume(account, 1, second.codes().getFirst())))
        .isTrue();
    assertThat(jdbc.queryForObject(RECOVERY_CODE_COUNT, Integer.class)).isEqualTo(9);
    assertThatThrownBy(() -> first.codes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void recoveryCodeConsumptionIsAtomicWithFactorPublicationAndHasOneConcurrentWinner()
      throws Exception {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var observed = authorityAt(SYNTHETIC_LOGIN).findFull("b".repeat(64), session).orElseThrow();
    var recovery = recoveryAt(SYNTHETIC_LOGIN);
    var issued = transaction().execute(status -> recovery.regenerate(observed));
    assertThat(issued).isNotNull();
    String code = issued.codes().getFirst();
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          assertThat(recovery.consume(account, 1, code)).isTrue();
                          jdbc.update(ADVANCE_EPOCH);
                          throw new com.hortinis.sync.authentication
                              .AuthenticationFailedException();
                        }))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThat(jdbc.queryForObject(RECOVERY_CODE_COUNT, Integer.class)).isEqualTo(10);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> transaction().<Boolean>execute(status -> recovery.consume(account, 1, code)));
      var second =
          executor.submit(
              () -> transaction().<Boolean>execute(status -> recovery.consume(account, 1, code)));
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(transaction().<Boolean>execute(status -> recovery.consume(account, 1, code)))
        .isFalse();
    assertThat(jdbc.queryForObject(RECOVERY_CODE_COUNT, Integer.class)).isEqualTo(9);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        DISABLE_CHANGE,
        EPOCH_CHANGE,
        MODE_CHANGE,
        SESSION_DELETE_CHANGE,
        "expired",
        "factor-time"
      })
  void retainedFullMetadataCannotReplaceCodesAfterAuthorityOrRecentFactorsChange(String change) {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var observed = authorityAt(SYNTHETIC_LOGIN).findFull("b".repeat(64), session).orElseThrow();
    var recovery = recoveryAt(SYNTHETIC_LOGIN);
    var issued = transaction().execute(status -> recovery.regenerate(observed));
    assertThat(issued).isNotNull();
    switch (change) {
      case DISABLE_CHANGE -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case EPOCH_CHANGE -> jdbc.update(ADVANCE_EPOCH);
      case MODE_CHANGE -> jdbc.update(SWITCH_NO_AUTH);
      case SESSION_DELETE_CHANGE -> jdbc.update("DELETE FROM authentication_session_authority");
      case "expired" ->
          jdbc.update(SESSION_UPDATE_PREFIX + "expires_at = created_at + INTERVAL '1 second'");
      case "factor-time" ->
          jdbc.update(
              SESSION_UPDATE_PREFIX
                  + "both_factors_at = both_factors_at - INTERVAL '300 "
                  + "seconds'");
      default -> throw new IllegalArgumentException("Unknown recovery fixture change.");
    }
    var later = recoveryAt(SYNTHETIC_LOGIN.plusSeconds(1));
    assertThatThrownBy(() -> transaction().execute(status -> later.regenerate(observed)))
        .isInstanceOfAny(
            com.hortinis.sync.authentication.AuthenticationRequiredException.class,
            com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThat(
            jdbc.queryForList(
                "SELECT secret_verifier FROM authentication_recovery_code", String.class))
        .containsExactlyInAnyOrderElementsOf(
            issued.codes().stream()
                .map(com.hortinis.sync.authentication.AuthenticationSecrets::digest)
                .toList());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        DISABLE_CHANGE,
        EPOCH_CHANGE,
        MODE_CHANGE,
        EXPIRY_CHANGE,
        FUTURE_CHANGE,
        "invalid-code"
      })
  void currentEpochExpiryAndExactSecretAreRequiredForRecoveryConsumption(String change) {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var observed = authorityAt(SYNTHETIC_LOGIN).findFull("b".repeat(64), session).orElseThrow();
    var issued = transaction().execute(status -> recoveryAt(SYNTHETIC_LOGIN).regenerate(observed));
    assertThat(issued).isNotNull();
    java.time.Instant now = SYNTHETIC_LOGIN;
    String code = issued.codes().getFirst();
    switch (change) {
      case DISABLE_CHANGE -> jdbc.update("UPDATE authentication_account SET status = 'disabled'");
      case EPOCH_CHANGE -> jdbc.update(ADVANCE_EPOCH);
      case MODE_CHANGE -> jdbc.update(SWITCH_NO_AUTH);
      case EXPIRY_CHANGE -> now = issued.expiresAt();
      case FUTURE_CHANGE -> now = now.minusSeconds(1);
      case "invalid-code" ->
          code = com.hortinis.sync.authentication.AuthenticationSecrets.randomValue();
      default -> throw new IllegalArgumentException("Unknown recovery consumption fixture change.");
    }
    var consuming = recoveryAt(now);
    String supplied = code;
    assertThat(transaction().<Boolean>execute(status -> consuming.consume(account, 1, supplied)))
        .isFalse();
    assertThat(jdbc.queryForObject(RECOVERY_CODE_COUNT, Integer.class))
        .isEqualTo(change.equals(EXPIRY_CHANGE) ? 0 : 10);
  }

  @Test
  void recoveryCodeOperationsRejectMissingOrReadOnlyPublicationTransactions() {
    UUID account = account();
    UUID session = fullSession(account, SYNTHETIC_LOGIN);
    var observed = authorityAt(SYNTHETIC_LOGIN).findFull("b".repeat(64), session).orElseThrow();
    var recovery = recoveryAt(SYNTHETIC_LOGIN);
    assertThatThrownBy(() -> recovery.regenerate(observed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> recovery.consume(account, 1, CANONICAL_RANDOM_FIXTURE))
        .isInstanceOf(IllegalStateException.class);
    var readOnly = transaction();
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> readOnly.execute(status -> recovery.regenerate(observed)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                readOnly.execute(status -> recovery.consume(account, 1, CANONICAL_RANDOM_FIXTURE)))
        .isInstanceOf(IllegalStateException.class);
  }

  private com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence
      recoveryAt(java.time.Instant now) {
    return new com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence(
        org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
        authorityAt(now));
  }

  @Test
  void anonymousAdmissionIsNonAuthorizingAndRollsBackWithTheCookieWorkflow() {
    jdbc.update(SWITCH_SINGLE_AUTH);
    var admission = admissionAt(SYNTHETIC_LOGIN);
    String sessionId = com.hortinis.sync.authentication.AuthenticationSecrets.randomValue();
    var reference = transaction().execute(status -> admission.admitAnonymous(sessionId));
    assertThat(reference).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT session_verifier FROM authentication_session_authority", String.class))
        .isEqualTo(com.hortinis.sync.authentication.AuthenticationSecrets.digest(sessionId))
        .isNotEqualTo(sessionId);
    assertThat(
            jdbc.queryForObject("SELECT state FROM authentication_session_authority", String.class))
        .isEqualTo("anonymous");
    assertThat(
            jdbc.queryForObject(
                "SELECT account_id FROM authentication_session_authority", UUID.class))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                    "SELECT expires_at FROM authentication_session_authority",
                    java.time.OffsetDateTime.class)
                .toInstant())
        .isEqualTo(SYNTHETIC_LOGIN.plusSeconds(300));
    assertThat(
            authorityAt(SYNTHETIC_LOGIN)
                .findFull(
                    com.hortinis.sync.authentication.AuthenticationSecrets.digest(sessionId),
                    reference.value()))
        .isEmpty();
    assertThatThrownBy(() -> transaction().execute(status -> admission.admitAnonymous(sessionId)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isEqualTo(1);
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          admission.admitAnonymous(
                              com.hortinis.sync.authentication.AuthenticationSecrets.randomValue());
                          throw new com.hortinis.sync.authentication
                              .AuthenticationFailedException();
                        }))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isEqualTo(1);
  }

  @Test
  void actualProductionAdmissionLimitCountsEveryPartialStateWithoutEvictingThem() {
    UUID account = account();
    seedAnonymousCapacity(1000);
    jdbc.update(
        SESSION_STATE_UPDATE_PREFIX
            + "= 'password', account_id = ?, authority_epoch = 1 "
            + "WHERE session_reference = (SELECT "
            + "session_reference FROM "
            + "authentication_session_authority ORDER BY "
            + "logout_target LIMIT 1)",
        account);
    jdbc.update(
        SESSION_STATE_UPDATE_PREFIX
            + "= 'recovery', recovery_kind = 'passkey', "
            + "account_id = ?, authority_epoch = 1 WHERE "
            + "session_reference = (SELECT session_reference "
            + "FROM authentication_session_authority ORDER BY "
            + "logout_target LIMIT 1 OFFSET 1)",
        account);
    jdbc.update(
        SESSION_STATE_UPDATE_PREFIX
            + "= 'setup', staged_login_identifier = "
            + "'synthetic-setup', staged_password_verifier = ?, "
            + "staged_user_handle = ? WHERE session_reference = "
            + "(SELECT session_reference FROM "
            + "authentication_session_authority ORDER BY "
            + "logout_target LIMIT 1 OFFSET 2)",
        SYNTHETIC_VERIFIER,
        new byte[32]);
    var admission = admissionAt(SYNTHETIC_LOGIN);
    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status ->
                            admission.admitAnonymous(
                                com.hortinis.sync.authentication.AuthenticationSecrets
                                    .randomValue())))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationOverloadedException.class);
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isEqualTo(1000);
    assertThat(
            jdbc.queryForList("SELECT state FROM authentication_session_authority", String.class))
        .contains("password", "recovery", "setup", "anonymous");
  }

  @Test
  void concurrentLastSlotAdmissionHasExactlyOneWinnerAtTheRealThousandSessionLimit()
      throws Exception {
    seedAnonymousCapacity(999);
    var admission = admissionAt(SYNTHETIC_LOGIN);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> tryAdmit(admission));
      var second = executor.submit(() -> tryAdmit(admission));
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isEqualTo(1000);
  }

  @Test
  void admissionPurgesExpiredUnownedAuthorityAndItsExactTransportWhilePreservingCurrentRows() {
    jdbc.update(SWITCH_SINGLE_AUTH);
    var admission = admissionAt(SYNTHETIC_LOGIN);
    String oldId = com.hortinis.sync.authentication.AuthenticationSecrets.randomValue();
    var expired = transaction().execute(status -> admission.admitAnonymous(oldId));
    assertThat(expired).isNotNull();
    String transport = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO spring_session (primary_id, "
            + "session_id, creation_time, last_access_time, "
            + "max_inactive_interval, expiry_time) VALUES (?, ?, "
            + "0, 0, 300, 300000)",
        transport,
        oldId);
    byte[] encoded =
        com.hortinis.sync.authentication.SessionAttributeCodec.conversionService()
            .convert(expired, byte[].class);
    jdbc.update(
        "INSERT INTO spring_session_attributes VALUES (?, 'hortinis.authority', ?)",
        transport,
        encoded);
    var stillCurrent =
        transaction()
            .execute(
                status ->
                    admissionAt(SYNTHETIC_LOGIN.plusSeconds(1))
                        .admitAnonymous(
                            com.hortinis.sync.authentication.AuthenticationSecrets.randomValue()));
    assertThat(stillCurrent).isNotNull();
    transaction()
        .executeWithoutResult(
            status ->
                admissionAt(SYNTHETIC_LOGIN.plusSeconds(300))
                    .admitAnonymous(
                        com.hortinis.sync.authentication.AuthenticationSecrets.randomValue()));
    assertThat(
            jdbc.queryForList(
                "SELECT session_reference FROM authentication_session_authority", UUID.class))
        .doesNotContain(expired.value())
        .contains(stillCurrent.value());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(SESSION_ATTRIBUTE_COUNT, Integer.class)).isZero();
  }

  @Test
  void admissionRejectsUnsupportedModeInvalidIdsAndMissingOrReadOnlyTransactions() {
    var admission = admissionAt(SYNTHETIC_LOGIN);
    String id = com.hortinis.sync.authentication.AuthenticationSecrets.randomValue();
    assertThatThrownBy(() -> admission.admitAnonymous(id))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> transaction().execute(status -> admission.admitAnonymous(id)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    jdbc.update(SWITCH_NO_AUTH);
    assertThatThrownBy(() -> transaction().execute(status -> admission.admitAnonymous(id)))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
    jdbc.update(SWITCH_SINGLE_AUTH);
    assertThatThrownBy(
            () ->
                transaction()
                    .execute(status -> admission.admitAnonymous(UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
    var readOnly = transaction();
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> readOnly.execute(status -> admission.admitAnonymous(id)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject(SESSION_AUTHORITY_COUNT, Integer.class)).isZero();
  }

  private com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence
      admissionAt(java.time.Instant now) {
    return new com.hortinis.sync.authentication.persistence
        .AuthenticationSessionAdmissionPersistence(
        org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
  }

  private void seedAnonymousCapacity(int count) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    jdbc.update(
        SESSION_INSERT_PREFIX
            + SESSION_COLUMNS_PREFIX
            + "created_at, expires_at) SELECT md5(n::text) || "
            + "md5('synthetic-verifier-' || n::text), "
            + "lpad(n::text, 42, '0') || 'A', 'anonymous', ?, ? "
            + "FROM generate_series(1, ?) n",
        java.sql.Timestamp.from(SYNTHETIC_LOGIN),
        java.sql.Timestamp.from(SYNTHETIC_LOGIN.plusSeconds(300)),
        count);
  }

  private int tryAdmit(
      com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence
          admission) {
    try {
      transaction()
          .execute(
              status ->
                  admission.admitAnonymous(
                      com.hortinis.sync.authentication.AuthenticationSecrets.randomValue()));
      return 1;
    } catch (com.hortinis.sync.authentication.AuthenticationOverloadedException exception) {
      return 0;
    }
  }

  private UUID passwordSession(UUID account) {
    return passwordSession(account, SYNTHETIC_LOGIN);
  }

  private UUID passwordSession(UUID account, java.time.Instant created) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    return jdbc.queryForObject(
        SESSION_INSERT_PREFIX
            + SESSION_COLUMNS_PREFIX
            + "account_id, authority_epoch, created_at, "
            + "expires_at) VALUES (?, ?, 'password', ?, 1, ?, ?) "
            + "RETURNING session_reference",
        UUID.class,
        com.hortinis.sync.authentication.AuthenticationSecrets.digest(
            com.hortinis.sync.authentication.AuthenticationSecrets.randomValue()),
        com.hortinis.sync.authentication.AuthenticationSecrets.randomValue(),
        account,
        java.sql.Timestamp.from(created),
        java.sql.Timestamp.from(created.plusSeconds(300)));
  }

  private com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence
      challengesAt(java.time.Instant now) {
    return new com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence(
        org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource())));
  }

  private void issueChallenge(
      com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence challenges,
      UUID parent,
      byte[] value) {
    transaction()
        .executeWithoutResult(
            status ->
                challenges.issue(
                    parent,
                    com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN,
                    value,
                    null));
  }

  private void assertChallengeSpent(
      com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence challenges,
      UUID parent) {
    assertThat(jdbc.queryForObject(CHALLENGE_COUNT, Integer.class)).isZero();
    assertThatThrownBy(
            () ->
                challenges.consume(
                    parent, com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN))
        .isInstanceOf(com.hortinis.sync.authentication.AuthenticationFailedException.class);
  }

  private int tryConsume(
      com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence challenges,
      UUID parent) {
    try {
      challenges.consume(
          parent, com.hortinis.sync.authentication.AuthenticationChallengePurpose.LOGIN);
      return 1;
    } catch (com.hortinis.sync.authentication.AuthenticationFailedException exception) {
      return 0;
    }
  }

  private int tryIssue(
      com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence challenges,
      UUID parent) {
    try {
      issueChallenge(challenges, parent, new byte[32]);
      return 1;
    } catch (com.hortinis.sync.authentication.AuthenticationOverloadedException exception) {
      return 0;
    }
  }

  private UUID fullSession(UUID account, java.time.Instant login) {
    jdbc.update(SWITCH_SINGLE_AUTH);
    return jdbc.queryForObject(
        SESSION_INSERT_PREFIX
            + SESSION_COLUMNS_PREFIX
            + "account_id, authority_epoch, created_at, "
            + "expires_at, full_login_at, last_interaction_at, "
            + "both_factors_at) VALUES (?, ?, 'full', ?, 1, ?, "
            + "?, ?, ?, ?) RETURNING session_reference",
        UUID.class,
        "b".repeat(64),
        CANONICAL_RANDOM_FIXTURE,
        account,
        java.sql.Timestamp.from(login),
        java.sql.Timestamp.from(login.plusSeconds(43200)),
        java.sql.Timestamp.from(login),
        java.sql.Timestamp.from(login),
        java.sql.Timestamp.from(login));
  }

  private com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence
      authorityAt(java.time.Instant now) {
    return new com.hortinis.sync.authentication.persistence
        .CurrentAuthenticationAuthorityPersistence(
        org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
        java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
  }

  private org.springframework.transaction.support.TransactionTemplate transaction() {
    return new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource())));
  }

  private static void requireAuthority(
      com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence
          authority,
      com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence
              .FullAuthority
          observed) {
    authority.requireCurrent(
        observed.account(),
        observed.sessionReference(),
        observed.epoch(),
        observed.instance(),
        observed.scope(),
        observed.mode());
  }

  private static void await(java.util.concurrent.CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test synchronization timed out.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Test synchronization interrupted.");
    }
  }

  private UUID account() {
    return jdbc.queryForObject(
        "INSERT INTO authentication_account "
            + "(server_instance_id, synchronization_scope_id, "
            + "login_identifier, status, password_verifier, "
            + "user_handle, created_at) SELECT "
            + "server_instance_id, synchronization_scope_id, "
            + "'synthetic-gardener', 'active', ?, ?, "
            + "CURRENT_TIMESTAMP FROM "
            + SCOPE_WHERE
            + "technical_default RETURNING account_id",
        UUID.class,
        SYNTHETIC_VERIFIER,
        new byte[32]);
  }

  private UUID anonymous() {
    return jdbc.queryForObject(
        SESSION_INSERT_PREFIX
            + SESSION_COLUMNS_PREFIX
            + "created_at, expires_at) VALUES (?, ?, "
            + "'anonymous', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
            + "+ INTERVAL '5 minutes') RETURNING "
            + "session_reference",
        UUID.class,
        "b".repeat(64),
        CANONICAL_RANDOM_FIXTURE);
  }

  private void assertSingleConsumer(String sql, UUID reference) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  jdbc.query(sql, (row, number) -> row.getObject(1, UUID.class), reference).size());
      var second =
          executor.submit(
              () ->
                  jdbc.query(sql, (row, number) -> row.getObject(1, UUID.class), reference).size());
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
  }
}
