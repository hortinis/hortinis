package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.minimum-idle=0",
      "spring.datasource.hikari.connection-timeout=250",
      "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '250ms'"
    })
@AutoConfigureMockMvc
class TransientSynchronizationIntegrationTest {

  private static final String OPERATIONS = "/api/v1/sync/operations";
  private static final String CHANGES = "/api/v1/sync/changes";
  private static final String UNAVAILABLE =
      "{\"code\":\"SYNCHRONIZATION_UNAVAILABLE\","
          + "\"message\":\"The synchronization service is unavailable.\"}";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @AfterEach
  void clearData() {
    jdbc.execute("DROP TRIGGER IF EXISTS transient_journal_failure ON technical_record_change");
    jdbc.execute("DROP FUNCTION IF EXISTS transient_journal_failure()");
    jdbc.execute(
        "TRUNCATE technical_record_change, technical_record_tombstone, "
            + "retired_technical_record_identifier, accepted_technical_record_operation, "
            + "technical_record RESTART IDENTITY CASCADE");
  }

  @Test
  void poolExhaustionReturns503ForPushAndPullThenRecovers() throws Exception {
    String request = createRequest();
    try (Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection()) {
      assertThat(first.isClosed()).isFalse();
      assertThat(second.isClosed()).isFalse();
      assertUnavailable(submit(request));
      assertUnavailable(mvc.perform(get(CHANGES)));
    }
    assertNoAcceptance();
    assertStableAcceptance(request);
    mvc.perform(get(CHANGES)).andExpect(status().isOk());
  }

  @Test
  void publicationLockTimeoutRollsBackTheReceiptAndAllowsIdenticalReplay() throws Exception {
    String request = createRequest();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SELECT scope_id FROM technical_synchronization_scope FOR UPDATE");
      try {
        assertUnavailable(submit(request));
        assertNoAcceptance();
      } finally {
        connection.rollback();
      }
    }
    assertStableAcceptance(request);
  }

  @Test
  void serializationFailureRollsBackEarlierRecordAndReceiptWrites() throws Exception {
    String request = createRequest();
    jdbc.execute(
        "CREATE FUNCTION transient_journal_failure() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''private journal value'' USING ERRCODE = ''40001''; END;' ");
    jdbc.execute(
        "CREATE TRIGGER transient_journal_failure BEFORE INSERT ON technical_record_change "
            + "FOR EACH ROW EXECUTE FUNCTION transient_journal_failure()");
    assertUnavailable(submit(request));
    assertNoAcceptance();
    jdbc.execute("DROP TRIGGER transient_journal_failure ON technical_record_change");
    assertStableAcceptance(request);
  }

  @Test
  void connectionOutageReturns503AndRecoversWithoutRestartingTheApplication() throws Exception {
    String request = createRequest();
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.isValid(5)).isTrue();
    }
    // The Docker client is owned by Testcontainers, including subsequent container teardown.
    POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    try {
      ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
      assertUnavailable(submit(request));
      assertUnavailable(mvc.perform(get(CHANGES)));
    } finally {
      POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }
    // Verify the restored server independently before borrowing a pooled connection.
    try (Connection connection = adminConnection()) {
      assertThat(connection.isValid(5)).isTrue();
    }
    assertNoAcceptance();
    assertStableAcceptance(request);
  }

  private Connection adminConnection() throws java.sql.SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private ResultActions submit(String request) throws Exception {
    return mvc.perform(post(OPERATIONS).contentType(MediaType.APPLICATION_JSON).content(request));
  }

  private static void assertUnavailable(ResultActions result) throws Exception {
    result
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json(UNAVAILABLE, true));
  }

  private void assertNoAcceptance() {
    assertThat(count("technical_record")).isZero();
    assertThat(count("accepted_technical_record_operation")).isZero();
    assertThat(count("technical_record_change")).isZero();
  }

  private void assertStableAcceptance(String request) throws Exception {
    String first =
        submit(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    submit(request).andExpect(status().isOk()).andExpect(content().json(first, true));
    assertThat(count("technical_record")).isEqualTo(1);
    assertThat(count("accepted_technical_record_operation")).isEqualTo(1);
    assertThat(count("technical_record_change")).isEqualTo(1);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static String createRequest() {
    return "{\"operationId\":\""
        + UUID.randomUUID()
        + "\",\"recordId\":\""
        + UUID.randomUUID()
        + "\",\"kind\":\"create\",\"value\":\"pending value\"}";
  }
}
