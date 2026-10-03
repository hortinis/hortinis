package com.hortinis.sync.persistence;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

/** Completes Spring's JDBC translation for PostgreSQL lock timeouts. */
public final class PostgreSqlExceptionTranslator extends SQLExceptionSubclassTranslator {

  private static final String LOCK_NOT_AVAILABLE = "55P03";

  @Override
  protected DataAccessException doTranslate(String task, String sql, SQLException exception) {
    if (LOCK_NOT_AVAILABLE.equals(exception.getSQLState())) {
      return new CannotAcquireLockException("The database lock is unavailable.", exception);
    }
    return super.doTranslate(task, sql, exception);
  }
}
