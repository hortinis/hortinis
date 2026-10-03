package com.hortinis.sync.protocol;

import com.hortinis.sync.access.AccountId;
import com.hortinis.sync.access.LocalDataSetId;
import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationScopeId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Strict wire parsing only; it cannot resolve or construct server authority. */
public final class AccessContractParser {

  private static final String CONTRACT_VERSION = "contractVersion";
  private static final String SERVER_INSTANCE_ID = "serverInstanceId";
  private static final String ACCESS_MODE = "accessMode";
  private static final String GENERATION = "generation";
  private static final String IDENTITY_FIELD = "identity";
  private static final String BOUNDARY_FIELD = "boundary";
  private static final String EXPECTATION_FIELD = "expectation";
  private static final String SYNCHRONIZATION_SCOPE_ID = "synchronizationScopeId";
  private static final String CONFIGURED = "configured";
  private static final String G2 = "g2";

  private static final Pattern EXPECTATION_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
  private static final Pattern ORIGIN_PATTERN = Pattern.compile("^https?://[a-z0-9.\\[\\]:%-]+$");
  private static final Set<String> STATES =
      Set.of(
          "offline",
          "binding_pending",
          "bound",
          "auth_required",
          "binding_mismatch",
          "revoked",
          "reconciliation_required",
          "scope_deleted",
          "disconnected");

  private AccessContractParser() {}

  /** Parses a named contract without coercing JSON types or ignoring unknown fields. */
  public static Object parse(String schema, JsonNode value) {
    try {
      return switch (schema) {
        case "LocalDataSetId" -> new LocalDataSetId(text(value));
        case "ServerInstanceId" -> new ServerInstanceId(text(value));
        case "SynchronizationScopeId" -> new SynchronizationScopeId(text(value));
        case "AccountId" -> new AccountId(text(value));
        case "ServerAccessMode" -> ServerAccessMode.parse(text(value));
        case "ExchangeExpectation" -> expectation(value);
        case "ServerOrigin" -> origin(value);
        case "SynchronizationBoundary" -> boundary(value);
        case "AuthorizedScopeIdentity" -> identity(value);
        case "ServerCapabilities" -> capabilities(value);
        case "SynchronizationBootstrap" -> bootstrap(value);
        case "ConfirmEmptyBindingRequest" -> confirmation(value);
        case "LocalServerBinding" -> binding(value);
        case "LocalExchangeState" -> exchange(value);
        case "AuthenticationRequiredError" ->
            error(value, "AUTHENTICATION_REQUIRED", "Authentication is required.");
        case "AccessDeniedError" -> error(value, "ACCESS_DENIED", "Access is denied.");
        case "BindingPreconditionFailedError" ->
            error(
                value, "BINDING_PRECONDITION_FAILED", "The binding precondition no longer holds.");
        case "AccessUnavailableError" ->
            error(value, "ACCESS_UNAVAILABLE", "The access service is unavailable.");
        default -> throw new IllegalArgumentException("The access schema is unsupported.");
      };
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException();
    }
  }

  private static AccessWireContracts.Capabilities capabilities(JsonNode value) {
    fields(
        value,
        CONTRACT_VERSION,
        SERVER_INSTANCE_ID,
        ACCESS_MODE,
        "supportedBoundaries",
        "anchoredSnapshots");
    require("1".equals(text(value.get(CONTRACT_VERSION))));
    JsonNode boundaries = value.get("supportedBoundaries");
    require(boundaries.isArray() && boundaries.size() >= 1 && boundaries.size() <= 2);
    List<String> supported = new ArrayList<>();
    for (JsonNode item : boundaries) {
      String kind = text(item);
      require(G2.equals(kind) || GENERATION.equals(kind));
      require(!supported.contains(kind));
      supported.add(kind);
    }
    return new AccessWireContracts.Capabilities(
        new ServerInstanceId(text(value.get(SERVER_INSTANCE_ID))),
        ServerAccessMode.parse(text(value.get(ACCESS_MODE))),
        supported,
        bool(value.get("anchoredSnapshots")));
  }

  private static AccessWireContracts.Bootstrap bootstrap(JsonNode value) {
    fields(
        value,
        CONTRACT_VERSION,
        SERVER_INSTANCE_ID,
        IDENTITY_FIELD,
        "population",
        BOUNDARY_FIELD,
        EXPECTATION_FIELD);
    require("1".equals(text(value.get(CONTRACT_VERSION))));
    String population = text(value.get("population"));
    require("empty".equals(population) || "populated".equals(population));
    return new AccessWireContracts.Bootstrap(
        new ServerInstanceId(text(value.get(SERVER_INSTANCE_ID))),
        identity(value.get(IDENTITY_FIELD)),
        population,
        boundary(value.get(BOUNDARY_FIELD)),
        expectation(value.get(EXPECTATION_FIELD)));
  }

  private static AccessWireContracts.Confirmation confirmation(JsonNode value) {
    fields(value, EXPECTATION_FIELD, "expectedPopulation");
    require("empty".equals(text(value.get("expectedPopulation"))));
    return new AccessWireContracts.Confirmation(expectation(value.get(EXPECTATION_FIELD)));
  }

  private static AccessWireContracts.ScopeIdentity identity(JsonNode value) {
    require(value != null && value.isObject());
    String authority = text(value.get("authority"));
    if (CONFIGURED.equals(authority)) {
      fields(value, "authority", ACCESS_MODE, SYNCHRONIZATION_SCOPE_ID);
      require("single-user-no-auth".equals(text(value.get(ACCESS_MODE))));
      return new AccessWireContracts.ConfiguredIdentity(
          new SynchronizationScopeId(text(value.get(SYNCHRONIZATION_SCOPE_ID))));
    }
    fields(value, "authority", ACCESS_MODE, SYNCHRONIZATION_SCOPE_ID, "accountId");
    require("authenticated".equals(authority));
    ServerAccessMode mode = ServerAccessMode.parse(text(value.get(ACCESS_MODE)));
    require(mode != ServerAccessMode.SINGLE_USER_NO_AUTH);
    return new AccessWireContracts.AuthenticatedIdentity(
        mode,
        new SynchronizationScopeId(text(value.get(SYNCHRONIZATION_SCOPE_ID))),
        new AccountId(text(value.get("accountId"))));
  }

  private static AccessWireContracts.Boundary boundary(JsonNode value) {
    require(value != null && value.isObject());
    String kind = text(value.get("kind"));
    if (G2.equals(kind)) {
      fields(value, "kind");
      return new AccessWireContracts.G2Boundary();
    }
    fields(value, "kind", GENERATION);
    require(GENERATION.equals(kind));
    String generation = text(value.get(GENERATION));
    require(!generation.isEmpty());
    return new AccessWireContracts.GenerationBoundary(generation);
  }

  private static AccessWireContracts.LocalBinding binding(JsonNode value) {
    fields(
        value,
        "version",
        "localDataSetId",
        "serverOrigin",
        SERVER_INSTANCE_ID,
        IDENTITY_FIELD,
        BOUNDARY_FIELD);
    require("1".equals(text(value.get("version"))));
    return new AccessWireContracts.LocalBinding(
        new LocalDataSetId(text(value.get("localDataSetId"))),
        origin(value.get("serverOrigin")),
        new ServerInstanceId(text(value.get(SERVER_INSTANCE_ID))),
        identity(value.get(IDENTITY_FIELD)),
        boundary(value.get(BOUNDARY_FIELD)));
  }

  private static AccessWireContracts.ExchangeState exchange(JsonNode value) {
    fields(value, "state", "exchangeFence", "logoutPending");
    String state = text(value.get("state"));
    String fence = text(value.get("exchangeFence"));
    boolean pending = bool(value.get("logoutPending"));
    require(STATES.contains(state) && UuidRules.isPositiveDecimal(fence));
    require(!pending || "auth_required".equals(state));
    return new AccessWireContracts.ExchangeState(state, fence, pending);
  }

  private static AccessWireContracts.AccessError error(
      JsonNode value, String code, String message) {
    fields(value, "code", "message");
    require(code.equals(text(value.get("code"))) && message.equals(text(value.get("message"))));
    return new AccessWireContracts.AccessError(code, message);
  }

  private static String expectation(JsonNode value) {
    String result = text(value);
    require(EXPECTATION_PATTERN.matcher(result).matches());
    return result;
  }

  private static String origin(JsonNode value) {
    String result = text(value);
    require(result.length() <= 2048 && ORIGIN_PATTERN.matcher(result).matches());
    return result;
  }

  private static String text(JsonNode value) {
    require(value != null && value.isTextual());
    return value.textValue();
  }

  private static boolean bool(JsonNode value) {
    require(value != null && value.isBoolean());
    return value.booleanValue();
  }

  private static void fields(JsonNode value, String... expected) {
    require(value != null && value.isObject());
    require(new HashSet<>(value.propertyNames()).equals(Set.of(expected)));
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new InvalidRequestException();
    }
  }
}
