package com.hortinis.sync.protocol;

import com.hortinis.sync.access.AccountId;
import com.hortinis.sync.access.LocalDataSetId;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationScopeId;
import java.util.List;

/** Parsed observations and local metadata, never trusted server access contexts. */
public interface AccessWireContracts {

  /** Boundary variants keep generation presence explicit. */
  public sealed interface Boundary permits GenerationlessBoundary, GenerationBoundary {}

  /** Synchronization without a server generation identifier. */
  public record GenerationlessBoundary() implements Boundary {}

  /** Future generation metadata does not advance a client cursor. */
  public record GenerationBoundary(String generation) implements Boundary {}

  /** Authorized identity observed on the wire, never proof of permission. */
  public sealed interface ScopeIdentity permits ConfiguredIdentity, AuthenticatedIdentity {}

  /** Observed explicit no-auth identity. */
  public record ConfiguredIdentity(SynchronizationScopeId synchronizationScopeId)
      implements ScopeIdentity {}

  /** Observed authenticated account/scope relationship. */
  public record AuthenticatedIdentity(
      ServerAccessMode accessMode,
      SynchronizationScopeId synchronizationScopeId,
      AccountId accountId)
      implements ScopeIdentity {}

  /** Public compatibility information. */
  public record Capabilities(
      ServerInstanceId serverInstanceId,
      ServerAccessMode accessMode,
      List<String> supportedBoundaries,
      boolean anchoredSnapshots) {

    /** Prevents later mutation of parsed capabilities. */
    public Capabilities {
      supportedBoundaries = List.copyOf(supportedBoundaries);
    }
  }

  /** An authorized observation with a non-authorizing expectation. */
  public record Bootstrap(
      ServerInstanceId serverInstanceId,
      ScopeIdentity identity,
      String population,
      Boundary boundary,
      String expectation) {

    @Override
    public String toString() {
      return "Bootstrap[redacted]";
    }
  }

  /** The only supported first-binding confirmation precondition. */
  public record Confirmation(String expectation) {

    @Override
    public String toString() {
      return "Confirmation[redacted]";
    }
  }

  /** Version-one local-only destination provenance. */
  public record LocalBinding(
      LocalDataSetId localDataSetId,
      String serverOrigin,
      ServerInstanceId serverInstanceId,
      ScopeIdentity identity,
      Boundary boundary) {

    @Override
    public String toString() {
      return "LocalBinding[redacted]";
    }
  }

  /** Local readiness; never authority for server access. */
  public record ExchangeState(String state, String exchangeFence, boolean logoutPending) {}

  /** Fixed generic access errors contain no existence or identity information. */
  public record AccessError(String code, String message) {}
}
