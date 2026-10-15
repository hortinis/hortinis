package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.authentication.AuthenticationSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One ordered projection and mapper for the internal session snapshot. */
final class AuthenticationSessionRows {
  static final String COLUMNS =
      "session_reference, account_id, authority_epoch, state, created_at, "
          + "expires_at, full_login_at, both_factors_at, "
          + "staged_login_identifier, staged_password_verifier, staged_user_handle, "
          + "logout_target, recovery_kind, replacement_credential_id";

  private AuthenticationSessionRows() {}

  static AuthenticationSession read(ResultSet row, int number) throws SQLException {
    return new AuthenticationSession(
        row.getObject(1, UUID.class),
        row.getObject(2, UUID.class),
        row.getLong(3),
        row.getString(4),
        row.getTimestamp(5).toInstant(),
        row.getTimestamp(6).toInstant(),
        instant(row, 7),
        instant(row, 8),
        row.getString(9),
        row.getString(10),
        row.getBytes(11),
        row.getString(12),
        row.getString(13),
        row.getBytes(14));
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
