package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.access.SyntheticAccessContexts;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.OperationIdReusedException;
import com.hortinis.sync.protocol.RecordId;
import com.hortinis.sync.protocol.RecordIdentifierRetiredException;
import com.hortinis.sync.protocol.RecordNotFoundException;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.SyncCursorCodec;
import com.hortinis.sync.protocol.TombstoneTechnicalChange;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "hortinis.access.mode=single-user-no-auth",
      "hortinis.access.public-origin=http://localhost"
    })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ScopedSynchronizationIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private SynchronizationAccessResolver technicalContext;
  @Autowired private TechnicalRecordSynchronizationService service;
  @Autowired private TechnicalRecordAcceptancePersistence persistence;
  @Autowired private TransactionTemplate transaction;
  @Autowired private MockMvc mvc;

  private SynchronizationAccessContext scopeA;
  private SynchronizationAccessContext scopeB;

  @BeforeEach
  void provisionSyntheticScopes() {
    scopeA = technicalContext.resolve();
    UUID scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO technical_synchronization_scope "
            + "(synchronization_scope_id, server_instance_id) VALUES (?, ?)",
        scope,
        UUID.fromString(scopeA.serverInstanceId().value()));
    scopeB =
        SyntheticAccessContexts.configured(
            scopeA.serverInstanceId(), new SynchronizationScopeId(scope.toString()));
  }

  @AfterEach
  void clearSyntheticData() {
    jdbc.execute("DROP TRIGGER IF EXISTS scoped_journal_failure ON technical_record_change");
    jdbc.execute("DROP FUNCTION IF EXISTS scoped_journal_failure()");
    jdbc.execute(
        "TRUNCATE technical_record_change, technical_record_tombstone, "
            + "retired_technical_record_identifier, "
            + "accepted_technical_record_operation, technical_record CASCADE");
    jdbc.update(
        "UPDATE technical_synchronization_scope SET technical_default = "
            + "TRUE WHERE synchronization_scope_id = ?",
        UUID.fromString(scopeA.synchronizationScopeId().value()));
    jdbc.update("DELETE FROM technical_synchronization_scope WHERE NOT technical_default");
    jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 0");
  }

  @Test
  void identicalIdentifiersHaveIndependentReceiptsRecordsRevisionsAndRetirement() {
    var createA = create("scope A");
    var createB =
        new CreateTechnicalRecordOperation(createA.operationId(), createA.recordId(), "scope B");
    var acceptedA = service.submit(scopeA, createA);
    var acceptedB = service.submit(scopeB, createB);
    assertThat(acceptedA.sequence()).isEqualTo("1");
    assertThat(acceptedB.sequence()).isEqualTo("1");
    assertThat(service.submit(scopeA, createA)).isEqualTo(acceptedA);
    assertThat(service.submit(scopeB, createB)).isEqualTo(acceptedB);
    assertThatThrownBy(() -> service.submit(scopeA, createB))
        .isInstanceOf(OperationIdReusedException.class);
    var replace =
        new ReplaceTechnicalRecordOperation(
            operationId(), createA.recordId(), "A replaced", ExpectedRevision.parse("1"));
    service.submit(scopeA, replace);
    var deletion =
        new DeleteTechnicalRecordOperation(
            operationId(), createA.recordId(), ExpectedRevision.parse("2"));
    var deleted = service.submit(scopeA, deletion);
    assertThat(service.submit(scopeA, deletion)).isEqualTo(deleted);
    assertThat(service.pull(scopeA, null).changes())
        .hasSize(3)
        .last()
        .isInstanceOf(TombstoneTechnicalChange.class);
    assertThat(service.pull(scopeB, null).changes()).hasSize(1);
    assertThat(persistence.findResult(scopeB, replace.operationId())).isEmpty();
    assertThat(persistence.isIdentifierRetired(scopeA, createA.recordId())).isTrue();
    assertThat(persistence.isIdentifierRetired(scopeB, createA.recordId())).isFalse();
    assertThatThrownBy(
            () ->
                service.submit(
                    scopeA,
                    new CreateTechnicalRecordOperation(operationId(), createA.recordId(), "new")))
        .isInstanceOf(RecordIdentifierRetiredException.class);
    var replaceB =
        new ReplaceTechnicalRecordOperation(
            replace.operationId(), createA.recordId(), "B replaced", ExpectedRevision.parse("1"));
    var resultB = (RecordOperationResult) service.submit(scopeB, replaceB);
    assertThat(resultB.record().value()).isEqualTo("B replaced");
    assertThat(resultB.record().revision().value()).isEqualTo(2);
    assertThat(resultB.sequence()).isEqualTo("2");
    var deletionB =
        new DeleteTechnicalRecordOperation(
            deletion.operationId(), createB.recordId(), ExpectedRevision.parse("2"));
    assertThat(service.submit(scopeB, deletionB).sequence()).isEqualTo("3");
    assertThat(service.pull(scopeB, null).changes())
        .hasSize(3)
        .last()
        .isInstanceOf(TombstoneTechnicalChange.class);
    assertThat(service.submit(scopeA, createA)).isEqualTo(acceptedA);
    assertThat(service.submit(scopeB, createB)).isEqualTo(acceptedB);
  }

  @Test
  void absentForeignAndDestroyedContextsDenyBeforeResourceLookup() {
    var operation = create("synthetic private value");
    service.submit(scopeA, operation);
    assertThatThrownBy(
            () ->
                service.submit(
                    scopeB,
                    new DeleteTechnicalRecordOperation(
                        operationId(), operation.recordId(), ExpectedRevision.parse("1"))))
        .isInstanceOf(RecordNotFoundException.class);
    var foreignInstance =
        SyntheticAccessContexts.configured(
            new ServerInstanceId(UUID.randomUUID().toString()), scopeA.synchronizationScopeId());
    var absentScope =
        SyntheticAccessContexts.configured(
            scopeA.serverInstanceId(), new SynchronizationScopeId(UUID.randomUUID().toString()));
    jdbc.update(
        "DELETE FROM technical_synchronization_scope WHERE synchronization_scope_id = ?",
        UUID.fromString(scopeB.synchronizationScopeId().value()));
    for (var context :
        new SynchronizationAccessContext[] {null, foreignInstance, absentScope, scopeB}) {
      assertThatThrownBy(() -> service.submit(context, operation))
          .isInstanceOf(SynchronizationAccessDeniedException.class)
          .hasMessage("Access is denied.");
      assertThatThrownBy(() -> service.pull(context, null))
          .isInstanceOf(SynchronizationAccessDeniedException.class);
      assertThatThrownBy(() -> persistence.findResult(context, operation.operationId()))
          .isInstanceOf(SynchronizationAccessDeniedException.class);
      assertThatThrownBy(() -> persistence.insertReceipt(context, operation))
          .isInstanceOf(SynchronizationAccessDeniedException.class);
    }
    assertThat(counter(scopeA)).isEqualTo(1);
  }

  @Test
  void cursorsRejectForeignScopesInstallationsFuturePositionsAndLegacyValues() {
    service.submit(scopeA, create("A"));
    service.submit(scopeB, create("B"));
    String cursorA = service.pull(scopeA, null).nextCursor();
    assertThat(service.pull(scopeA, cursorA).changes()).isEmpty();
    assertThat(service.pull(scopeA, cursorA).nextCursor()).isEqualTo(cursorA);
    for (String cursor :
        new String[] {
          cursorA,
          "v1.MQ",
          SyncCursorCodec.encode(2, scopeB.serverInstanceId(), scopeB.synchronizationScopeId()),
          SyncCursorCodec.encode(
              0,
              new ServerInstanceId(UUID.randomUUID().toString()),
              scopeB.synchronizationScopeId())
        }) {
      assertThatThrownBy(() -> service.pull(scopeB, cursor))
          .isInstanceOf(InvalidRequestException.class);
    }
  }

  @Test
  void compositeConstraintsRejectForeignReceiptsRecordMismatchAndUnknownScopes() {
    var operation = create("A");
    service.submit(scopeA, operation);
    UUID a = UUID.fromString(scopeA.synchronizationScopeId().value());
    UUID b = UUID.fromString(scopeB.synchronizationScopeId().value());
    UUID op = UUID.fromString(operation.operationId().toWire());
    UUID record = UUID.fromString(operation.recordId().toWire());
    assertThatThrownBy(() -> insertRawChange(b, op, record, 1))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> insertRawChange(a, op, UUID.randomUUID(), 2))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> insertRawChange(a, op, record, 2))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO technical_record VALUES (?, ?, 1, 'synthetic')",
                    UUID.randomUUID(),
                    record))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO accepted_technical_record_operation "
                        + "(synchronization_scope_id, operation_id, operation_kind, "
                        + "record_id, operation_value, expected_revision) "
                        + "VALUES (?, ?, 'create', ?, 'synthetic', 1)",
                    a,
                    UUID.randomUUID(),
                    record))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void journalFailureRollsBackEveryWriteAndTheScopeCounter() {
    var operation = create("rollback");
    jdbc.execute(
        "CREATE FUNCTION scoped_journal_failure() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''synthetic failure''; END;'");
    jdbc.execute(
        "CREATE TRIGGER scoped_journal_failure BEFORE INSERT ON technical_record_change "
            + "FOR EACH ROW EXECUTE FUNCTION scoped_journal_failure()");
    assertThatThrownBy(() -> service.submit(scopeA, operation))
        .isInstanceOf(RuntimeException.class);
    assertThat(counter(scopeA)).isZero();
    assertThat(persistence.findReceipt(scopeA, operation.operationId())).isEmpty();
    assertThat(persistence.findRecord(scopeA, operation.recordId())).isEmpty();
    assertThat(service.pull(scopeA, null).changes()).isEmpty();
    jdbc.execute("DROP TRIGGER scoped_journal_failure ON technical_record_change");
    assertThat(service.submit(scopeA, operation).sequence()).isEqualTo("1");
  }

  @Test
  void applicationFailureAndSequenceExhaustionLeaveNoAcceptance() {
    var operation = create("rollback");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      service.submit(scopeA, operation);
                      throw new IllegalStateException("synthetic rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(counter(scopeA)).isZero();
    assertThat(persistence.findResult(scopeA, operation.operationId())).isEmpty();
    jdbc.update(
        "UPDATE technical_synchronization_scope SET "
            + "last_published_sequence = ? WHERE synchronization_scope_id = ?",
        Long.MAX_VALUE,
        UUID.fromString(scopeA.synchronizationScopeId().value()));
    assertThatThrownBy(() -> service.submit(scopeA, operation))
        .isInstanceOf(RuntimeException.class);
    assertThat(counter(scopeA)).isEqualTo(Long.MAX_VALUE);
    assertThat(persistence.findReceipt(scopeA, operation.operationId())).isEmpty();
    assertThat(persistence.findRecord(scopeA, operation.recordId())).isEmpty();
    assertThat(service.submit(scopeB, operation).sequence()).isEqualTo("1");
  }

  @Test
  void blockedPublisherCannotBeOvertakenInItsScopeAndDoesNotBlockAnotherScope() throws Exception {
    var first = create("uncommitted A");
    var second = create("later A");
    CountDownLatch published = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      Future<?> firstWriter =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        service.submit(scopeA, first);
                        published.countDown();
                        await(release);
                      }));
      try {
        assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> secondWriter = executor.submit(() -> service.submit(scopeA, second));
        awaitBlockedPublisher();
        assertThat(secondWriter.isDone()).isFalse();
        var independent =
            executor.submit(() -> service.submit(scopeB, first)).get(5, TimeUnit.SECONDS);
        assertThat(independent.sequence()).isEqualTo("1");
        assertThat(service.pull(scopeA, null).changes()).isEmpty();
        assertThat(service.pull(scopeB, null).changes()).hasSize(1);
        release.countDown();
        firstWriter.get(5, TimeUnit.SECONDS);
        secondWriter.get(5, TimeUnit.SECONDS);
        assertThat(service.pull(scopeA, null).changes())
            .extracting(change -> change.operationId())
            .containsExactly(first.operationId(), second.operationId());
        assertThat(counter(scopeA)).isEqualTo(2);
        assertThat(counter(scopeB)).isEqualTo(1);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void scopePaginationDoesNotAdvanceAcrossOtherScopes() {
    for (int index = 0; index < 101; index++) {
      service.submit(scopeA, create("A"));
      service.submit(scopeB, create("B"));
    }
    var first = service.pull(scopeA, null);
    assertThat(first.changes()).hasSize(100);
    assertThat(first.hasMore()).isTrue();
    var last = service.pull(scopeA, first.nextCursor());
    assertThat(last.changes())
        .hasSize(1)
        .first()
        .extracting(change -> change.sequence())
        .isEqualTo("101");
    assertThat(last.hasMore()).isFalse();
    assertThat(counter(scopeB)).isEqualTo(101);
  }

  @Test
  void missingTechnicalScopeDeniesHttpWithoutLoggingStableIdentities(CapturedOutput output)
      throws Exception {
    jdbc.update("UPDATE technical_synchronization_scope SET technical_default = FALSE");
    String payload =
        "{\"operationId\":\""
            + UUID.randomUUID()
            + "\",\"recordId\":\""
            + UUID.randomUUID()
            + "\",\"kind\":\"create\",\"value\":\"private-synthetic-canary\"}";
    mvc.perform(post("/api/v1/sync/operations").contentType("application/json").content(payload))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            content().json("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access is denied.\"}", true));
    mvc.perform(get("/api/v1/sync/changes")).andExpect(status().isForbidden());
    assertThat(output.getOut())
        .doesNotContain(
            scopeA.serverInstanceId().value(),
            scopeA.synchronizationScopeId().value(),
            scopeB.synchronizationScopeId().value(),
            "private-synthetic-canary");
  }

  private long counter(SynchronizationAccessContext context) {
    return jdbc.queryForObject(
        "SELECT last_published_sequence FROM "
            + "technical_synchronization_scope WHERE synchronization_scope_id = "
            + "?",
        Long.class,
        UUID.fromString(context.synchronizationScopeId().value()));
  }

  private void insertRawChange(UUID scope, UUID operation, UUID record, long sequence) {
    jdbc.update(
        "INSERT INTO technical_record_change "
            + "(synchronization_scope_id, server_sequence, operation_id, "
            + "record_id, revision, value, change_kind) "
            + "VALUES (?, ?, ?, ?, 1, 'synthetic', 'record')",
        scope,
        sequence,
        operation,
        record);
  }

  private void awaitBlockedPublisher() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      int blocked =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                  + "AND query LIKE '%technical_synchronization_scope%' AND "
                  + "cardinality(pg_blocking_pids(pid)) > 0",
              Integer.class);
      if (blocked > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("The same-scope publisher did not block on the scope row.");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("The controlled transaction was not released.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("The controlled transaction was interrupted.", exception);
    }
  }

  private static CreateTechnicalRecordOperation create(String value) {
    return new CreateTechnicalRecordOperation(
        operationId(), RecordId.parse(UUID.randomUUID().toString()), value);
  }

  private static OperationId operationId() {
    return OperationId.parse(UUID.randomUUID().toString());
  }
}
