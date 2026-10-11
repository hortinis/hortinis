package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hortinis.sync.access.BindingPreconditionFailedException;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.access.SynchronizationAccessUnavailableException;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.access.SyntheticAccessContexts;
import com.hortinis.sync.persistence.SynchronizationExpectationPersistence;
import com.hortinis.sync.protocol.AccessContractParser;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.RecordId;
import com.hortinis.sync.service.BoundSynchronizationService;
import com.hortinis.sync.service.SynchronizationBindingService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "hortinis.access.mode=single-user-no-auth",
      "hortinis.access.public-origin=http://localhost"
    })
@AutoConfigureMockMvc
class SynchronizationBindingIntegrationTest {
  private static final String EXPECTATION_HEADER = "Sync-Expectation";
  private static final String CACHE_CONTROL = "Cache-Control";
  private static final String NO_STORE = "no-store";
  private static final String BOOTSTRAP = "/api/v1/sync/bootstrap";
  private static final String CONFIRM = "/api/v1/sync/bindings/confirm";
  private static final String OPERATIONS = "/api/v1/sync/operations";
  private static final String CHANGES = "/api/v1/sync/changes";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SynchronizationAccessResolver resolver;
  @Autowired private SynchronizationBindingService binding;
  @Autowired private BoundSynchronizationService exchange;
  @Autowired private SynchronizationExpectationPersistence expectations;
  @Autowired private com.hortinis.sync.persistence.ServerInstallationPersistence installation;
  @Autowired private com.hortinis.sync.persistence.SynchronizationScopePersistence scopes;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

  @AfterEach
  void resetSyntheticData() {
    jdbc.execute("DROP TRIGGER IF EXISTS binding_failure ON technical_record_change");
    jdbc.execute("DROP FUNCTION IF EXISTS binding_failure()");
    jdbc.execute(
        "TRUNCATE technical_exchange_expectation, technical_record_change, "
            + "technical_record_tombstone, retired_technical_record_identifier, "
            + "accepted_technical_record_operation, technical_record CASCADE");
    jdbc.update("DELETE FROM technical_synchronization_scope WHERE NOT technical_default");
    jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 0");
  }

  @Test
  void publicDiscoveryAndProtectedBootstrapMatchClosedNoStoreContracts() throws Exception {
    JsonNode capabilities =
        body(
            mvc.perform(get("/api/v1/server/capabilities"))
                .andExpect(status().isOk())
                .andExpect(header().string(CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.anchoredSnapshots").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString());
    AccessContractParser.parse("ServerCapabilities", capabilities);
    assertThat(capabilities.propertyNames())
        .containsExactlyInAnyOrder(
            "contractVersion",
            "serverInstanceId",
            "accessMode",
            "supportedBoundaries",
            "anchoredSnapshots");
    assertThat(countExpectations()).isZero();
    JsonNode observation =
        body(
            mvc.perform(get(BOOTSTRAP))
                .andExpect(status().isOk())
                .andExpect(header().string(CACHE_CONTROL, NO_STORE))
                .andReturn()
                .getResponse()
                .getContentAsString());
    AccessContractParser.parse("SynchronizationBootstrap", observation);
    assertThat(observation.path("population").textValue()).isEqualTo("empty");
    assertThat(observation.path("boundary").path("kind").textValue()).isEqualTo("generationless");
    assertThat(countExpectations()).isEqualTo(1);
    assertThat(countReceipts()).isZero();
  }

  @Test
  void productionLaunchCannotActivateOrLoadTopologyAuthority() throws Exception {
    var context = resolver.resolve();
    for (String marker : new String[] {"A1", "A2", "B", "unknown"}) {
      mvc.perform(get(BOOTSTRAP).header("X-Hortinis-Test-Device", marker))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.identity.synchronizationScopeId")
                  .value(context.synchronizationScopeId().value()));
    }
    ClassLoader runtime = Thread.currentThread().getContextClassLoader();
    assertThat(runtime.getResource("com/hortinis/topology/TopologyTestApplication.class")).isNull();
    assertThat(runtime.getResource("com/hortinis/topology/TopologyTestConfiguration.class"))
        .isNull();
    assertThat(runtime.getResource("com/hortinis/sync/access/TopologyAccessResolver.class"))
        .isNull();
  }

  @Test
  void confirmationIsIdempotentWithoutCreatingOwnershipOrExtendingLifetime() throws Exception {
    var context = resolver.resolve();
    var observation = binding.bootstrap(context);
    String before = expiry(observation.expectation());
    String request =
        JSON.writeValueAsString(
            java.util.Map.of(
                "expectation", observation.expectation(), "expectedPopulation", "empty"));
    for (int attempt = 0; attempt < 2; attempt++) {
      JsonNode confirmed =
          body(
              mvc.perform(post(CONFIRM).contentType("application/json").content(request))
                  .andExpect(status().isOk())
                  .andExpect(header().string(CACHE_CONTROL, NO_STORE))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      AccessContractParser.parse("SynchronizationBootstrap", confirmed);
      assertThat(confirmed.path("expectation").textValue()).isEqualTo(observation.expectation());
    }
    assertThat(expiry(observation.expectation())).isEqualTo(before);
    assertThat(phase(observation.expectation())).isEqualTo("confirmed");
    assertThat(countReceipts()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM technical_synchronization_scope", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void missingDuplicateMalformedAndUnconfirmedExpectationsFailBeforeDataLookup() throws Exception {
    String observed = binding.bootstrap(resolver.resolve()).expectation();
    mvc.perform(post(OPERATIONS).contentType("application/json").content("{}"))
        .andExpect(status().isConflict())
        .andExpect(header().string(CACHE_CONTROL, NO_STORE))
        .andExpect(jsonPath("$.code").value("BINDING_PRECONDITION_FAILED"));
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, observed, observed))
        .andExpect(status().isConflict());
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, "private-header-canary!"))
        .andExpect(status().isConflict());
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, observed).param("cursor", "malformed"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BINDING_PRECONDITION_FAILED"));
    assertThat(countReceipts()).isZero();
  }

  @Test
  void racingFirstPublisherInvalidatesOtherAdmissionsAndAcceptedReplayStaysIdempotent() {
    var context = resolver.resolve();
    String first = confirmed(context);
    String second = confirmed(context);
    var operation = operation();
    var accepted = exchange.submit(context, first, operation);
    assertThat(exchange.submit(context, first, operation)).isEqualTo(accepted);
    assertThatThrownBy(() -> exchange.submit(context, second, operation()))
        .isInstanceOf(BindingPreconditionFailedException.class);
    assertThatThrownBy(() -> binding.confirm(context, second))
        .isInstanceOf(BindingPreconditionFailedException.class);
    assertThat(countReceipts()).isEqualTo(1);
    assertThat(phase(first)).isEqualTo("established");
    assertThat(phase(second)).isEqualTo("confirmed");
  }

  @Test
  void concurrentFirstPublicationsHaveExactlyOneWinner() throws Exception {
    var context = resolver.resolve();
    String first = confirmed(context);
    String second = confirmed(context);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
      for (String token : new String[] {first, second}) {
        results.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Synthetic race did not start.");
                  }
                  try {
                    exchange.submit(context, token, operation());
                    return true;
                  } catch (BindingPreconditionFailedException denied) {
                    return false;
                  }
                }));
      }
      assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int accepted = 0;
      for (var result : results) {
        if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(1);
      assertThat(countReceipts()).isEqualTo(1);
    } finally {
      start.countDown();
    }
  }

  @Test
  void reobservationRetainsLifetimeAndStateWithoutAllocatingRows() throws Exception {
    var context = resolver.resolve();
    String token = confirmed(context);
    String before = expiry(token);
    for (int index = 0; index < 150; index++) {
      mvc.perform(get(BOOTSTRAP).header(EXPECTATION_HEADER, token))
          .andExpect(status().isOk())
          .andExpect(header().string(CACHE_CONTROL, NO_STORE))
          .andExpect(jsonPath("$.expectation").value(token));
    }
    assertThat(countExpectations()).isEqualTo(1);
    assertThat(expiry(token)).isEqualTo(before);
    mvc.perform(get(BOOTSTRAP).header(EXPECTATION_HEADER, token, token))
        .andExpect(status().isConflict());
    mvc.perform(get(BOOTSTRAP).header(EXPECTATION_HEADER, "invalid!"))
        .andExpect(status().isConflict());
    // A new service object reads the same database state; no in-memory issuance grants authority.
    var recreated = new SynchronizationBindingService(installation, scopes, expectations);
    var observed =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(ignored -> recreated.reobserve(context, token));
    assertThat(observed.expectation()).isEqualTo(token);
  }

  @Test
  void failedPublicationRollsBackAdmissionAndAllAcceptanceEvidence() {
    var context = resolver.resolve();
    String admission = confirmed(context);
    var operation = operation();
    jdbc.execute(
        "CREATE FUNCTION binding_failure() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''Synthetic publication failure'' "
            + "USING ERRCODE = ''23514''; END;'");
    jdbc.execute(
        "CREATE TRIGGER binding_failure BEFORE INSERT ON technical_record_change "
            + "FOR EACH ROW EXECUTE FUNCTION binding_failure()");
    assertThatThrownBy(() -> exchange.submit(context, admission, operation))
        .isInstanceOf(RuntimeException.class);
    assertThat(phase(admission)).isEqualTo("confirmed");
    assertThat(countReceipts()).isZero();
    jdbc.execute("DROP TRIGGER binding_failure ON technical_record_change");
    assertThat(exchange.submit(context, admission, operation).operationId())
        .isEqualTo(operation.operationId());
    assertThat(countReceipts()).isEqualTo(1);
  }

  @Test
  void expiryAndForeignScopeTokenFailGenericallyAndCannotConfirmOrExchange() throws Exception {
    var context = resolver.resolve();
    String expired = confirmed(context);
    jdbc.update(
        "UPDATE technical_exchange_expectation SET expires_at = "
            + "clock_timestamp() - INTERVAL '1 second' WHERE expectation = ?",
        expired);
    assertThatThrownBy(() -> binding.confirm(context, expired))
        .isInstanceOf(BindingPreconditionFailedException.class);
    UUID otherScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO technical_synchronization_scope "
            + "(synchronization_scope_id, server_instance_id) VALUES (?, ?)",
        otherScope,
        UUID.fromString(context.serverInstanceId().value()));
    var other =
        SyntheticAccessContexts.configured(
            context.serverInstanceId(), new SynchronizationScopeId(otherScope.toString()));
    String foreign = confirmed(other);
    for (String token : new String[] {expired, foreign, UUID.randomUUID().toString()}) {
      mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, token))
          .andExpect(status().isConflict())
          .andExpect(header().string(CACHE_CONTROL, NO_STORE))
          .andExpect(jsonPath("$.message").value("The binding precondition no longer holds."));
    }
    expectations.purgeExpired();
    assertThat(countExpectations()).isEqualTo(1);
    assertThat(countReceipts()).isZero();
  }

  @Test
  void reservationsAndHistoryKeepAnOtherwiseEmptyProjectionPopulated() {
    var context = resolver.resolve();
    jdbc.update(
        "INSERT INTO retired_technical_record_identifier VALUES (?, ?, 1)",
        UUID.fromString(context.synchronizationScopeId().value()),
        UUID.randomUUID());
    assertThat(binding.bootstrap(context).empty()).isFalse();
  }

  @Test
  void outstandingObservationLimitIsScopedAndCleanupPermitsFreshIssuance() {
    var context = resolver.resolve();
    for (int index = 0; index < 128; index++) {
      binding.bootstrap(context);
    }
    assertThatThrownBy(() -> binding.bootstrap(context))
        .isInstanceOf(SynchronizationAccessUnavailableException.class);
    jdbc.update(
        "UPDATE technical_exchange_expectation SET expires_at = "
            + "clock_timestamp() - INTERVAL '1 second'");
    assertThat(binding.bootstrap(context).empty()).isTrue();
    assertThat(countExpectations()).isEqualTo(1);
  }

  private String confirmed(SynchronizationAccessContext context) {
    String token = binding.bootstrap(context).expectation();
    binding.confirm(context, token);
    return token;
  }

  private String expiry(String token) {
    return jdbc.queryForObject(
        "SELECT expires_at::text FROM technical_exchange_expectation WHERE expectation = ?",
        String.class,
        token);
  }

  private String phase(String token) {
    return jdbc.queryForObject(
        "SELECT phase FROM technical_exchange_expectation WHERE expectation = ?",
        String.class,
        token);
  }

  private int countExpectations() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM technical_exchange_expectation", Integer.class);
  }

  private int countReceipts() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM accepted_technical_record_operation", Integer.class);
  }

  private static JsonNode body(String value) {
    return JSON.readTree(value);
  }

  private static CreateTechnicalRecordOperation operation() {
    return new CreateTechnicalRecordOperation(
        new OperationId(UUID.randomUUID().toString()),
        new RecordId(UUID.randomUUID().toString()),
        "Synthetic binding intent");
  }
}
