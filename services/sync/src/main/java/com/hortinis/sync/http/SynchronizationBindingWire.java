package com.hortinis.sync.http;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import com.hortinis.sync.service.SynchronizationBindingService;
import java.util.List;
import java.util.Map;

/** Explicit closed response shapes; internal contexts are never serialized. */
public final class SynchronizationBindingWire {
  private SynchronizationBindingWire() {}

  /** Serializes public capabilities without scope/account/setup fields. */
  public static CapabilitiesBody capabilities(
      ServerInstallationPersistence.PublicIdentity identity) {
    return new CapabilitiesBody(
        "1",
        identity.instance().value(),
        identity.mode().wireValue(),
        List.of("generationless"),
        false);
  }

  /** Serializes only the authorized destination observation. */
  public static BootstrapBody bootstrap(SynchronizationBindingService.Observation observation) {
    var context = observation.context();
    Map<String, String> identity =
        switch (context.authority()) {
          case SynchronizationAccessContext.ConfiguredAuthority ignored ->
              Map.of(
                  "authority",
                  "configured",
                  "accessMode",
                  "single-user-no-auth",
                  "synchronizationScopeId",
                  context.synchronizationScopeId().value());
          case SynchronizationAccessContext.AuthenticatedAuthority authority ->
              Map.of(
                  "authority",
                  "authenticated",
                  "accessMode",
                  authority.mode().wireValue(),
                  "synchronizationScopeId",
                  context.synchronizationScopeId().value(),
                  "accountId",
                  authority.accountId().value());
        };
    return new BootstrapBody(
        "1",
        context.serverInstanceId().value(),
        identity,
        observation.empty() ? "empty" : "populated",
        Map.of("kind", "generationless"),
        observation.expectation());
  }

  /** Public implemented capabilities; no software fingerprint or setup status. */
  public record CapabilitiesBody(
      String contractVersion,
      String serverInstanceId,
      String accessMode,
      List<String> supportedBoundaries,
      boolean anchoredSnapshots) {
    @Override
    public String toString() {
      return "CapabilitiesBody[redacted]";
    }
  }

  /** Closed non-authorizing observation; protocol identifiers stay out of diagnostics. */
  public record BootstrapBody(
      String contractVersion,
      String serverInstanceId,
      Map<String, String> identity,
      String population,
      Map<String, String> boundary,
      String expectation) {
    @Override
    public String toString() {
      return "BootstrapBody[redacted]";
    }
  }
}
