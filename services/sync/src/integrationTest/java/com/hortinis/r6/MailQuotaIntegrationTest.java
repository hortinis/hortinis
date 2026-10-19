package com.hortinis.r6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.persistence.MailIssuancePersistence;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Fresh R6 storage evidence, distinct from HTTP timing and actual SMTP/host workflow evidence. */
@Testcontainers
class MailQuotaIntegrationTest {
  private static final Instant START = Instant.parse("2026-10-19T12:00:00Z");
  private static final String DOMAIN = "@example.test";
  private static final String OVERFLOW = "overflow@example.test";
  private static final String EMAIL = "Mailbox@Example.test";
  private static final String RESERVATIONS = "SELECT count(*) FROM authentication_mail_reservation";
  private static final String KEY = "SELECT hmac_key FROM authentication_mail_quota";
  private static final String RECORDS = "SELECT * FROM authentication_mail_reservation";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  private final MutableClock clock = new MutableClock();
  private JdbcTemplate jdbc;
  private DriverManagerDataSource source;
  private DataSourceTransactionManager manager;
  private TransactionTemplate transactions;
  private MailIssuancePersistence quotas;

  @BeforeEach
  void freshSchema() {
    String schema = "r6_quota_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    transactions = new TransactionTemplate(manager);
    quotas = reopen();
  }

  @Test
  void emailCooldownAndHourlyQuotaSharePurposesCaseAndIndependentInstances() {
    assertThat(reserve(EMAIL, ActionTokenPurpose.INVITATION)).isTrue();
    final byte[] key = jdbc.queryForObject(KEY, byte[].class);
    clock.advance(59);
    quotas = reopen();
    assertThat(reserve(EMAIL.toLowerCase(java.util.Locale.ROOT), ActionTokenPurpose.RESET))
        .isFalse();
    clock.advance(1);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    clock.advance(60);
    assertThat(reserve(EMAIL, ActionTokenPurpose.INVITATION)).isTrue();
    clock.advance(60);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isFalse();
    clock.advance(3420);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    assertThat(jdbc.queryForObject(KEY, byte[].class)).isEqualTo(key);
    assertThat(jdbc.queryForList(RECORDS).toString()).doesNotContain(EMAIL, "account_id", "secret");
  }

  @Test
  void emailDailyWindowExpiresExactlyAndCleanupCannotExtendIt() {
    for (int index = 0; index < 10; index++) {
      if (index > 0) {
        clock.advance(index % 3 == 0 ? 3600 : 60);
      }
      assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    }
    clock.advance(3600);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isFalse();
    clock.now = START.plusSeconds(86400);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    assertThat(count()).isEqualTo(10);
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void purposeHourlyAllocationCannotSpendOtherPurposeCapacity(ActionTokenPurpose purpose) {
    int maximum = purpose == ActionTokenPurpose.RESET ? 20 : 10;
    for (int index = 0; index < maximum; index++) {
      assertThat(reserve("synthetic" + index + DOMAIN, purpose)).isTrue();
    }
    assertThat(reserve(OVERFLOW, purpose)).isFalse();
    var other =
        purpose == ActionTokenPurpose.RESET
            ? ActionTokenPurpose.INVITATION
            : ActionTokenPurpose.RESET;
    assertThat(reserve("other@example.test", other)).isTrue();
    clock.advance(3600);
    assertThat(reserve(OVERFLOW, purpose)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(ActionTokenPurpose.class)
  void purposeDailyAllocationAndInstallationCeilingAreShared(ActionTokenPurpose purpose) {
    int hourly = purpose == ActionTokenPurpose.RESET ? 20 : 10;
    int daily = purpose == ActionTokenPurpose.RESET ? 80 : 20;
    for (int index = 0; index < daily; index++) {
      if (index > 0 && index % hourly == 0) {
        clock.advance(3600);
      }
      quotas = reopen();
      assertThat(reserve("synthetic" + index + DOMAIN, purpose)).isTrue();
    }
    clock.advance(3600);
    assertThat(reserve(OVERFLOW, purpose)).isFalse();
    var other =
        purpose == ActionTokenPurpose.RESET
            ? ActionTokenPurpose.INVITATION
            : ActionTokenPurpose.RESET;
    assertThat(reserve("other@example.test", other)).isTrue();
    clock.now = START.plusSeconds(86400);
    assertThat(reserve(OVERFLOW, purpose)).isTrue();
  }

  @Test
  void exactlyOneConcurrentFinalSlotCommitsAcrossIndependentConnections() throws Exception {
    for (int index = 0; index < 19; index++) {
      assertThat(reserve("synthetic" + index + DOMAIN, ActionTokenPurpose.RESET)).isTrue();
    }
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            var independent = reopen();
                            return transactions.execute(
                                status ->
                                    independent.reserve(
                                        "contender" + index + DOMAIN, ActionTokenPurpose.RESET));
                          }))
              .toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get(10, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
    }
    assertThat(count()).isEqualTo(20);
  }

  @Test
  void rollbackDoesNotSpendQuotaOrPublishNewKeyAndCommitBeforeCrashRemainsCharged() {
    transactions.executeWithoutResult(
        status -> {
          assertThat(quotas.reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
          status.setRollbackOnly();
        });
    assertThat(count()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authentication_mail_quota", Integer.class))
        .isZero();
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    // A new graph/connection cannot know whether the previous committed sender reached SMTP.
    quotas = reopen();
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isFalse();
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void actualDatabaseFailureRollsBackReservationAndHighWaterMark() {
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    final var before =
        jdbc.queryForObject(
            "SELECT last_observed_at FROM authentication_mail_quota", Timestamp.class);
    jdbc.execute(
        "CREATE FUNCTION reject_quota() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'synthetic-quota-canary'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_quota BEFORE INSERT ON authentication_mail_reservation "
            + "FOR EACH ROW EXECUTE FUNCTION reject_quota()");
    clock.advance(60);
    assertThatThrownBy(() -> reserve(EMAIL, ActionTokenPurpose.RESET))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(count()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_observed_at FROM authentication_mail_quota", Timestamp.class))
        .isEqualTo(before);
    jdbc.execute("DROP TRIGGER reject_quota ON authentication_mail_reservation");
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
  }

  @Test
  void backwardClockFailsClosedAcrossReopeningAndDoesNotPurgeRestrictions() {
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    clock.advance(-1);
    quotas = reopen();
    assertThatThrownBy(() -> reserve("other@example.test", ActionTokenPurpose.RESET))
        .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
    quotas.purge();
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void cleanupIsBoundedAndReusesInstallationKeyAfterAllWindowsExpire() {
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    final byte[] key = jdbc.queryForObject(KEY, byte[].class);
    jdbc.update(
        "INSERT INTO authentication_mail_reservation (email_key, purpose, issued_at) "
            + "SELECT repeat('a',64), 'reset', ? FROM generate_series(1, 1000)",
        Timestamp.from(START));
    clock.advance(86400);
    quotas.purge();
    assertThat(count()).isEqualTo(501);
    quotas.purge();
    assertThat(count()).isEqualTo(1);
    assertThat(reserve(EMAIL, ActionTokenPurpose.RESET)).isTrue();
    assertThat(count()).isEqualTo(1);
    assertThat(jdbc.queryForObject(KEY, byte[].class)).isEqualTo(key);
  }

  @Test
  void missingOrReadOnlyTransactionsCannotReserve() {
    assertThatThrownBy(() -> quotas.reserve(EMAIL, ActionTokenPurpose.RESET))
        .isInstanceOf(IllegalStateException.class);
    var readOnly = new TransactionTemplate(manager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> quotas.reserve(EMAIL, ActionTokenPurpose.RESET)))
        .isInstanceOf(IllegalStateException.class);
  }

  private boolean reserve(String email, ActionTokenPurpose purpose) {
    return Boolean.TRUE.equals(transactions.execute(status -> quotas.reserve(email, purpose)));
  }

  private int count() {
    return jdbc.queryForObject(RESERVATIONS, Integer.class);
  }

  private MailIssuancePersistence reopen() {
    return new MailIssuancePersistence(JdbcClient.create(source), clock, manager);
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now = START;

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
