package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.EmailAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Installation-wide rolling delivery reservations; no raw recipient or account/token mapping. */
public final class MailIssuancePersistence {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transactions;

  /** Shares the issuance datasource, trusted clock and Spring transaction manager. */
  public MailIssuancePersistence(JdbcClient jdbc, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.clock = clock;
    transactions = new TransactionTemplate(manager);
  }

  /** Caller must roll back this reservation when selection/issuance does not commit. */
  public boolean reserve(String email, ActionTokenPurpose purpose) {
    requireTransaction();
    final String canonical = EmailAddress.canonical(email);
    Instant now = now();
    byte[] candidate = new byte[32];
    new SecureRandom().nextBytes(candidate);
    try {
      jdbc.sql("SET LOCAL lock_timeout = '1s'").update();
      jdbc.sql(
              "INSERT INTO authentication_mail_quota "
                  + "(server_instance_id, hmac_key, last_observed_at) "
                  + "SELECT server_instance_id, ?, ? FROM technical_server_installation "
                  + "WHERE singleton ON CONFLICT DO NOTHING")
          .params(candidate, Timestamp.from(now))
          .update();
    } finally {
      Arrays.fill(candidate, (byte) 0);
    }
    var state =
        jdbc.sql(
                "SELECT hmac_key, last_observed_at FROM authentication_mail_quota "
                    + "WHERE singleton FOR UPDATE")
            .query((row, number) -> new State(row.getBytes(1), row.getTimestamp(2).toInstant()))
            .single();
    // Observe time after waiting for the shared lock, not at transaction start.
    now = now();
    if (now.isBefore(state.observed())) {
      Arrays.fill(state.key(), (byte) 0);
      throw new TransientDataAccessResourceException("Mail accounting clock is unavailable.");
    }
    String key = digest(state.key(), canonical);
    jdbc.sql("UPDATE authentication_mail_quota SET last_observed_at = ? WHERE singleton")
        .param(Timestamp.from(now))
        .update();
    purgeAt(now);
    var windows =
        jdbc.sql(
                "SELECT email_key, purpose, issued_at "
                    + "FROM authentication_mail_reservation WHERE issued_at > ?")
            .param(Timestamp.from(now.minus(Duration.ofDays(1))))
            .query(
                (row, number) ->
                    new Reservation(
                        row.getString(1), row.getString(2), row.getTimestamp(3).toInstant()))
            .list();
    final Instant cutoff = now.minus(Duration.ofHours(1));
    final Instant cooldown = now.minusSeconds(60);
    var emailWindow = windows.stream().filter(row -> row.key().equals(key)).toList();
    var purposeWindow =
        windows.stream().filter(row -> row.purpose().equals(purpose.value())).toList();
    boolean reset = purpose == ActionTokenPurpose.RESET;
    if (windows.size() >= 100
        || windows.stream().filter(row -> row.issued().isAfter(cutoff)).count() >= 30
        || emailWindow.size() >= 10
        || emailWindow.stream().filter(row -> row.issued().isAfter(cutoff)).count() >= 3
        || emailWindow.stream().anyMatch(row -> row.issued().isAfter(cooldown))
        || purposeWindow.size() >= (reset ? 80 : 20)
        || purposeWindow.stream().filter(row -> row.issued().isAfter(cutoff)).count()
            >= (reset ? 20 : 10)) {
      return false;
    }
    jdbc.sql(
            "INSERT INTO authentication_mail_reservation (email_key, purpose, issued_at) "
                + "VALUES (?, ?, ?)")
        .params(key, purpose.value(), Timestamp.from(now))
        .update();
    return true;
  }

  /** Startup/minute purge; logical windows never depend on physical cleanup progress. */
  public void purge() {
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL lock_timeout = '1s'").update();
          var observed =
              jdbc.sql(
                      "SELECT last_observed_at FROM authentication_mail_quota "
                          + "WHERE singleton FOR UPDATE")
                  .query((row, number) -> row.getTimestamp(1).toInstant())
                  .optional();
          Instant now = now();
          if (observed.isPresent() && !now.isBefore(observed.get())) {
            purgeAt(now);
          }
        });
  }

  private void purgeAt(Instant now) {
    jdbc.sql(
            "DELETE FROM authentication_mail_reservation WHERE reservation_id IN "
                + "(SELECT reservation_id FROM authentication_mail_reservation "
                + "WHERE issued_at <= ? "
                + "ORDER BY issued_at, reservation_id LIMIT 500)")
        .param(Timestamp.from(now.minus(Duration.ofDays(1))))
        .update();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String digest(byte[] key, String canonical) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Required mail accounting primitive is unavailable.");
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Mail accounting requires a writable transaction.");
    }
  }

  private record State(byte[] key, Instant observed) {
    @Override
    public String toString() {
      return "MailQuota[redacted]";
    }
  }

  private record Reservation(String key, String purpose, Instant issued) {
    @Override
    public String toString() {
      return "MailReservation[redacted]";
    }
  }
}
