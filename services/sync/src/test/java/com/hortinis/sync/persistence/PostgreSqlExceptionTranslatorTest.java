package com.hortinis.sync.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;

class PostgreSqlExceptionTranslatorTest {

  @ParameterizedTest
  @CsvSource({
    "55P03, CannotAcquireLockException",
    "40001, CannotAcquireLockException",
    "40P01, PessimisticLockingFailureException",
    "57014, QueryTimeoutException",
    "08006, DataAccessResourceFailureException",
    "23505, DuplicateKeyException",
    "42601, BadSqlGrammarException"
  })
  void translatesLockTimeoutAndPreservesSpringClassifications(String sqlState, String type) {
    DataAccessException translated =
        new PostgreSqlExceptionTranslator()
            .translate("query", null, new SQLException("private value", sqlState));
    assertThat(translated).isNotNull();
    assertThat(translated.getClass().getSimpleName()).isEqualTo(type);
  }
}
