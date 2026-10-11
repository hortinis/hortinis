package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hortinis.sync.persistence.ServerInstallationPersistence;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyAccessResolverTest {
  private static final String MARKER = "X-Hortinis-Test-Device";
  private static final ServerInstanceId INSTANCE =
      new ServerInstanceId("01890f3e-7c5a-7b12-8abc-000000000001");
  private static final SynchronizationScopeId DEFAULT =
      new SynchronizationScopeId("01890f3e-7c5a-7b12-8abc-000000000002");
  private final ServerInstallationPersistence installation =
      mock(ServerInstallationPersistence.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final TopologyAccessResolver resolver = new TopologyAccessResolver(installation, request);

  @Test
  void mapsOnlyClosedSyntheticDevicesAndRetainsTheSeparateDefaultRegressionScope() {
    when(installation.technicalIdentity())
        .thenReturn(new ServerInstallationPersistence.TechnicalIdentity(INSTANCE, DEFAULT));
    for (String marker : List.of("A1", "A2", "B")) {
      when(request.getHeaders(MARKER))
          .thenAnswer(ignored -> Collections.enumeration(List.of(marker)));
      assertThat(resolver.resolve().serverInstanceId()).isEqualTo(INSTANCE);
      assertThat(resolver.resolve().synchronizationScopeId().value())
          .isEqualTo(
              "B".equals(marker) ? TopologyAccessResolver.SCOPE_B : TopologyAccessResolver.SCOPE_A);
    }
    when(request.getHeaders(MARKER)).thenReturn(Collections.emptyEnumeration());
    assertThat(resolver.resolve().synchronizationScopeId()).isEqualTo(DEFAULT);
  }

  @Test
  void deniesUnknownMalformedAndAmbiguousDeviceMarkers() {
    when(installation.technicalIdentity())
        .thenReturn(new ServerInstallationPersistence.TechnicalIdentity(INSTANCE, DEFAULT));
    for (var markers :
        List.of(List.of("unknown"), List.of("A1, B"), List.of(" A1"), List.of("A1", "B"))) {
      when(request.getHeaders(MARKER)).thenReturn(Collections.enumeration(markers));
      assertThatThrownBy(resolver::resolve)
          .isInstanceOf(SynchronizationAccessDeniedException.class);
    }
  }

  @Test
  void cannotOverrideInvalidCommittedInstallationOrModeState() {
    when(installation.technicalIdentity()).thenThrow(new SynchronizationAccessDeniedException());
    when(request.getHeaders(MARKER)).thenReturn(Collections.enumeration(List.of("B")));
    assertThatThrownBy(resolver::resolve).isInstanceOf(SynchronizationAccessDeniedException.class);
  }
}
