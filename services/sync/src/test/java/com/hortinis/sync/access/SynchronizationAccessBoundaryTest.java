package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hortinis.sync.http.TechnicalSynchronizationController;
import com.hortinis.sync.http.TechnicalSynchronizationErrorHandler;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import com.hortinis.sync.persistence.SynchronizationScopePersistence;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.service.BoundSynchronizationService;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

class SynchronizationAccessBoundaryTest {
  private static final String EXPECTATION_HEADER = "Sync-Expectation";
  private static final String EXPECTATION = "synthetic-expectation";
  private static final String ID = "01890f3e-7c5a-7b12-8abc-0123456789ab";
  private static final String OPERATIONS = "/api/v1/sync/operations";
  private static final String CHANGES = "/api/v1/sync/changes";
  private final SynchronizationAccessResolver access = mock(SynchronizationAccessResolver.class);
  private final BoundSynchronizationService service = mock(BoundSynchronizationService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new TechnicalSynchronizationController(service, access))
          .setControllerAdvice(new TechnicalSynchronizationErrorHandler())
          .build();

  @Test
  void denialPrecedesOperationValidationAndCursorLookup() throws Exception {
    when(access.resolve()).thenThrow(new SynchronizationAccessDeniedException());
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, EXPECTATION)
                .contentType("application/json")
                .content("{\"synchronizationScopeId\":\"" + ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            content().json("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access is denied.\"}", true));
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, EXPECTATION).param("cursor", ID))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void absentResolverResultCannotReachTheService() throws Exception {
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, EXPECTATION)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, EXPECTATION))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void everyRequestResolvesBeforeDelegationWithTheSameImmutableContext() throws Exception {
    var context =
        SynchronizationAccessContext.configured(
            new ServerInstanceId(ID), new SynchronizationScopeId(ID));
    when(access.resolve()).thenReturn(context);
    when(service.pull(context, EXPECTATION, null))
        .thenReturn(new ChangePage(List.of(), "cursor", false));
    when(service.submit(eq(context), eq(EXPECTATION), any()))
        .thenAnswer(
            invocation -> {
              TechnicalRecordOperation operation = invocation.getArgument(2);
              return new RecordOperationResult(
                  "accepted",
                  operation.operationId(),
                  new com.hortinis.sync.protocol.TechnicalRecord(
                      operation.recordId(),
                      new com.hortinis.sync.protocol.Revision(1),
                      "synthetic"),
                  "1");
            });
    mvc.perform(
            post(OPERATIONS)
                .header(EXPECTATION_HEADER, EXPECTATION)
                .contentType("application/json")
                .content(
                    "{\"operationId\":\""
                        + ID
                        + "\",\"recordId\":\""
                        + ID
                        + "\",\"kind\":\"create\",\"value\":\"synthetic\"}"))
        .andExpect(status().isOk());
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, EXPECTATION)).andExpect(status().isOk());
    mvc.perform(get(CHANGES).header(EXPECTATION_HEADER, EXPECTATION)).andExpect(status().isOk());
    var order = inOrder(access, service);
    order.verify(access).resolve();
    order.verify(service).submit(eq(context), eq(EXPECTATION), any());
    order.verify(access).resolve();
    order.verify(service).pull(context, EXPECTATION, null);
    order.verify(access).resolve();
    order.verify(service).pull(context, EXPECTATION, null);
    order.verifyNoMoreInteractions();
  }

  @Test
  void configuredResolverReadsCurrentInstallationStateOnEveryCall() {
    var installation = mock(ServerInstallationPersistence.class);
    var identity =
        new ServerInstallationPersistence.TechnicalIdentity(
            new ServerInstanceId(ID), new SynchronizationScopeId(ID));
    when(installation.technicalIdentity())
        .thenReturn(identity)
        .thenThrow(new SynchronizationAccessDeniedException());
    var resolver = new ConfiguredSynchronizationAccessResolver(installation);
    assertThat(resolver.resolve().synchronizationScopeId()).isEqualTo(identity.scope());
    org.assertj.core.api.Assertions.assertThatThrownBy(resolver::resolve)
        .isInstanceOf(SynchronizationAccessDeniedException.class);
  }

  @Test
  void representativeArchitectureRulesRequireExplicitContextAndNarrowDelegation() {
    assertThat(TechnicalSynchronizationController.class.getConstructors()[0].getParameterTypes())
        .containsExactly(BoundSynchronizationService.class, SynchronizationAccessResolver.class);
    assertThat(TechnicalRecordSynchronizationService.class.getConstructors()[0].getParameterTypes())
        .containsExactly(
            TechnicalRecordAcceptancePersistence.class, SynchronizationScopePersistence.class);
    var methods =
        Arrays.stream(TechnicalRecordSynchronizationService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toList();
    assertThat(methods).hasSize(2);
    for (var method : methods) {
      assertThat(method.getParameterTypes()[0]).isEqualTo(SynchronizationAccessContext.class);
      assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }
    for (var method : TechnicalRecordAcceptancePersistence.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        assertThat(method.getParameterTypes()[0]).isEqualTo(SynchronizationAccessContext.class);
      }
    }
  }
}
