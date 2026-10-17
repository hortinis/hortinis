package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hortinis.sync.access.AccountId;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.access.SynchronizationScopeId;
import com.hortinis.sync.access.SyntheticAccessContexts;
import com.hortinis.sync.access.SyntheticAccessResolver;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.RecordId;
import com.hortinis.sync.service.BoundSynchronizationService;
import com.hortinis.sync.service.SynchronizationBindingService;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    properties = {
      "hortinis.access.mode=single-user-no-auth",
      "hortinis.access.public-origin=http://localhost"
    })
@AutoConfigureMockMvc
@Import(AccessResolutionIntegrationTest.SyntheticResolverConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AccessResolutionIntegrationTest {
  private static final String EXPECTATION_HEADER = "Sync-Expectation";
  private static final String OPERATIONS = "/api/v1/sync/operations";
  private static final String CHANGES = "/api/v1/sync/changes";
  private static final String PRIVATE_VALUE = "private-s4-synthetic-canary";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.0");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mvc;
  @Autowired private SyntheticAccessResolver access;
  @Autowired private ServerInstallationPersistence installation;
  @Autowired private TechnicalRecordSynchronizationService service;
  @Autowired private TechnicalRecordAcceptancePersistence persistence;

  @Autowired
  @Qualifier("synchronizationAccessResolver")
  private SynchronizationAccessResolver configuredAccess;

  @Autowired private SynchronizationBindingService binding;
  @Autowired private BoundSynchronizationService boundExchange;
  private String expectationA;
  private String expectationB;
  private SynchronizationAccessContext scopeA;
  private SynchronizationAccessContext scopeB;

  @BeforeEach
  void provisionScopes() {
    scopeA = configuredAccess.resolve();
    UUID b = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO technical_synchronization_scope "
            + "(synchronization_scope_id, server_instance_id) VALUES (?, ?)",
        b,
        UUID.fromString(scopeA.serverInstanceId().value()));
    scopeB =
        SyntheticAccessContexts.configured(
            scopeA.serverInstanceId(), new SynchronizationScopeId(b.toString()));
    expectationA = EstablishedBindingFixture.establish(binding, boundExchange, scopeA);
    expectationB = EstablishedBindingFixture.establish(binding, boundExchange, scopeB);
  }

  @AfterEach
  void clearScopes() {
    access.clear();
    jdbc.execute("TRUNCATE technical_exchange_expectation");
    jdbc.update("UPDATE technical_server_installation SET access_mode = 'single-user-no-auth'");
    jdbc.update(
        "UPDATE technical_synchronization_scope SET technical_default = TRUE "
            + "WHERE synchronization_scope_id = ?",
        UUID.fromString(scopeA.synchronizationScopeId().value()));
    jdbc.execute(
        "TRUNCATE technical_record_change, technical_record_tombstone, "
            + "retired_technical_record_identifier, accepted_technical_record_operation, "
            + "technical_record CASCADE");
    jdbc.update("DELETE FROM technical_synchronization_scope WHERE NOT technical_default");
    jdbc.update("UPDATE technical_synchronization_scope SET last_published_sequence = 0");
  }

  @Test
  void noAuthRoutesDoNotAllocateSessionsOrEmitCookiesWithBootSessionInfrastructure()
      throws Exception {
    access.select(scopeA);
    int before = jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
    for (String path :
        new String[] {
          "/actuator/health", "/api/v1/server/capabilities", "/api/v1/sync/bootstrap"
        }) {
      mvc.perform(
              get(path)
                  .cookie(
                      new jakarta.servlet.http.Cookie(
                          "__Host-hortinis-session",
                          com.hortinis.sync.authentication.AuthenticationSecrets.randomValue())))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist("Set-Cookie"));
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class))
        .isEqualTo(before);
  }

  @Test
  void httpDispatchKeepsIdenticalIdentifiersAndCursorsInsideResolvedScope(CapturedOutput output)
      throws Exception {
    String operation = UUID.randomUUID().toString();
    String record = UUID.randomUUID().toString();
    access.select(scopeA);
    submit(operation, record, PRIVATE_VALUE);
    final String cursorA = pull().path("nextCursor").textValue();
    access.select(scopeB);
    assertThat(pull().path("changes").size()).isZero();
    submit(operation, record, "independent B");
    JsonNode b = pull();
    assertThat(b.path("changes").size()).isEqualTo(1);
    assertThat(b.toString()).contains("independent B").doesNotContain(PRIVATE_VALUE);
    mvc.perform(
            get(CHANGES).header(EXPECTATION_HEADER, currentExpectation()).param("cursor", cursorA))
        .andExpect(status().isBadRequest());
    access.select(scopeA);
    assertThat(pull().toString()).contains(PRIVATE_VALUE).doesNotContain("independent B");
    assertThat(output.getAll())
        .doesNotContain(
            PRIVATE_VALUE,
            operation,
            record,
            cursorA,
            scopeA.serverInstanceId().value(),
            scopeA.synchronizationScopeId().value(),
            scopeB.synchronizationScopeId().value());
  }

  @Test
  void requestIdentitySubstitutionsCannotSelectAnotherScope() throws Exception {
    access.select(scopeA);
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, currentExpectation())
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Synchronization-Scope-Id", scopeB.synchronizationScopeId().value())
                .param("synchronizationScopeId", scopeB.synchronizationScopeId().value())
                .content(
                    payload(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), PRIVATE_VALUE)))
        .andExpect(status().isOk());
    mvc.perform(
            get(CHANGES)
                .header(EXPECTATION_HEADER, currentExpectation())
                .header("X-Synchronization-Scope-Id", scopeB.synchronizationScopeId().value())
                .param("synchronizationScopeId", scopeB.synchronizationScopeId().value()))
        .andExpect(status().isOk());
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, currentExpectation())
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content(
                    payload(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            PRIVATE_VALUE)
                        .replace(
                            "}",
                            ",\"synchronizationScopeId\":\""
                                + scopeB.synchronizationScopeId().value()
                                + "\"}")))
        .andExpect(status().isBadRequest());
    assertThat(service.pull(scopeA, null).changes()).hasSize(1);
    assertThat(service.pull(scopeB, null).changes()).isEmpty();
  }

  @Test
  void missingAndMismatchedAuthorityDeniesBeforeLookupWithoutLoggingIdentities(
      CapturedOutput output) throws Exception {
    denyHttp();
    for (var context :
        new SynchronizationAccessContext[] {
          SyntheticAccessContexts.configured(
              new ServerInstanceId(UUID.randomUUID().toString()), scopeA.synchronizationScopeId()),
          SyntheticAccessContexts.configured(
              scopeA.serverInstanceId(), new SynchronizationScopeId(UUID.randomUUID().toString())),
          SyntheticAccessContexts.authenticated(
              scopeA.serverInstanceId(),
              scopeA.synchronizationScopeId(),
              ServerAccessMode.SINGLE_USER_AUTH,
              new AccountId(UUID.randomUUID().toString())),
          SyntheticAccessContexts.authenticated(
              scopeA.serverInstanceId(),
              scopeA.synchronizationScopeId(),
              ServerAccessMode.MULTI_USER_AUTH,
              new AccountId(UUID.randomUUID().toString()))
        }) {
      access.select(context);
      denyHttp();
      var operation =
          new CreateTechnicalRecordOperation(
              OperationId.parse(UUID.randomUUID().toString()),
              RecordId.parse(UUID.randomUUID().toString()),
              PRIVATE_VALUE);
      assertThatThrownBy(() -> persistence.findReceipt(context, operation.operationId()))
          .isInstanceOf(SynchronizationAccessDeniedException.class);
      assertThatThrownBy(() -> persistence.insertReceipt(context, operation))
          .isInstanceOf(SynchronizationAccessDeniedException.class);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM accepted_technical_record_operation", Integer.class))
        .isZero();
    assertThat(output.getAll())
        .doesNotContain(
            PRIVATE_VALUE,
            scopeA.serverInstanceId().value(),
            scopeA.synchronizationScopeId().value());
  }

  @Test
  void browserCrossOriginMutationIsDeniedWithoutAcceptanceOrSensitiveDiagnostics(
      CapturedOutput output) throws Exception {
    access.select(scopeA);
    for (String contentType :
        new String[] {
          MediaType.APPLICATION_JSON_VALUE, "text/plain", "application/x-www-form-urlencoded"
        }) {
      mvc.perform(
              post(OPERATIONS)
                  .header(EXPECTATION_HEADER, currentExpectation())
                  .contentType(contentType)
                  .header("Origin", "https://private-origin-canary.example")
                  .header("Authorization", "Bearer private-credential-canary")
                  .header("Cookie", "private-session-canary")
                  .content(
                      payload(
                          UUID.randomUUID().toString(),
                          UUID.randomUUID().toString(),
                          PRIVATE_VALUE)))
          .andExpect(status().isForbidden())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    assertThat(service.pull(scopeA, null).changes()).isEmpty();
    assertThat(output.getAll())
        .doesNotContain(
            PRIVATE_VALUE,
            "private-origin-canary",
            "private-credential-canary",
            "private-session-canary");
  }

  @Test
  void noAuthRoutesAreUnavailableWithoutAllocatingAnAuthenticationSession() throws Exception {
    for (String path :
        java.util.List.of(
            "/api/v1/auth/csrf",
            "/api/v1/auth/session",
            "/api/v1/auth/login",
            "/api/v1/auth/logout")) {
      var response =
          mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL))
          .contains(org.springframework.http.CacheControl.noStore().getHeaderValue());
      assertThat(response.getHeader("Set-Cookie")).isNull();
      assertThat(response.getContentAsString()).contains("ACCESS_UNAVAILABLE");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
  }

  @Test
  void committedAuthenticatedModesRejectNoAuthConfigurationAndStaleContexts() throws Exception {
    for (String mode : new String[] {"single-user-auth", "multi-user-auth"}) {
      jdbc.update("UPDATE technical_server_installation SET access_mode = ?", mode);
      assertThatThrownBy(() -> installation.initializeMode(ServerAccessMode.SINGLE_USER_NO_AUTH))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("The configured access mode differs from committed setup.");
      assertThat(
              jdbc.queryForObject(
                  "SELECT access_mode FROM technical_server_installation", String.class))
          .isEqualTo(mode);
      assertThatThrownBy(configuredAccess::resolve)
          .isInstanceOf(SynchronizationAccessDeniedException.class);
      access.select(scopeA);
      denyHttp();
    }
  }

  @Test
  void initialModeCommitRollsBackWhenTheSoleScopeIsMissing() {
    jdbc.update("UPDATE technical_server_installation SET access_mode = NULL");
    jdbc.update("UPDATE technical_synchronization_scope SET technical_default = FALSE");
    assertThatThrownBy(() -> installation.initializeMode(ServerAccessMode.SINGLE_USER_NO_AUTH))
        .isInstanceOf(SynchronizationAccessDeniedException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT access_mode FROM technical_server_installation", String.class))
        .isNull();
    assertThatThrownBy(configuredAccess::resolve)
        .isInstanceOf(SynchronizationAccessDeniedException.class);
  }

  private void denyHttp() throws Exception {
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, currentExpectation())
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content(
                    payload(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), PRIVATE_VALUE)))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            content().json("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access is denied.\"}", true));
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, currentExpectation()))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  private String currentExpectation() {
    try {
      return access.resolve().synchronizationScopeId().equals(scopeB.synchronizationScopeId())
          ? expectationB
          : expectationA;
    } catch (SynchronizationAccessDeniedException exception) {
      return expectationA;
    }
  }

  private void submit(String operation, String record, String value) throws Exception {
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, currentExpectation())
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content(payload(operation, record, value)))
        .andExpect(status().isOk());
  }

  private JsonNode pull() throws Exception {
    return JsonMapper.builder()
        .build()
        .readTree(
            mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, currentExpectation()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
  }

  private static String payload(String operation, String record, String value) {
    return "{\"operationId\":\""
        + operation
        + "\",\"recordId\":\""
        + record
        + "\",\"kind\":\"create\",\"value\":\""
        + value
        + "\"}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SyntheticResolverConfiguration {
    @Bean
    @Primary
    SyntheticAccessResolver syntheticAccessResolver() {
      return new SyntheticAccessResolver();
    }
  }
}
