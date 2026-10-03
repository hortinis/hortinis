package com.hortinis.sync.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.protocol.AccessContractParser;
import com.hortinis.sync.protocol.AccessWireContracts;
import com.hortinis.sync.protocol.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccessConformanceFixturesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> SCHEMAS =
      Set.of(
          "LocalDataSetId",
          "ServerInstanceId",
          "SynchronizationScopeId",
          "AccountId",
          "ServerAccessMode",
          "ExchangeExpectation",
          "ServerOrigin",
          "SynchronizationBoundary",
          "AuthorizedScopeIdentity",
          "ServerCapabilities",
          "SynchronizationBootstrap",
          "ConfirmEmptyBindingRequest",
          "LocalServerBinding",
          "LocalExchangeState",
          "AuthenticationRequiredError",
          "AccessDeniedError",
          "BindingPreconditionFailedError",
          "AccessUnavailableError");

  @Test
  void consumesEveryAccessFixtureAndRejectsInvalidShapes() throws IOException {
    int consumed = 0;
    Set<String> covered = new java.util.HashSet<>();
    for (JsonNode declaration : fixture("capabilities.json").get("fixtures")) {
      if (!declaration.has("suite") || !"access".equals(declaration.get("suite").textValue())) {
        continue;
      }
      assertThat(declaration.get("capability").textValue()).isEqualTo("access-contract-parsing");
      assertThat(declaration.get("consumers").toString()).contains("\"java\"");
      String file = declaration.get("file").textValue();
      JsonNode groups = fixture(file).get("groups");
      assertThat(groups.size()).isPositive();
      for (JsonNode examples : groups) {
        String schema = examples.get("schema").textValue().replace(".json", "");
        assertThat(SCHEMAS).contains(schema);
        assertThat(covered.add(schema)).as("unique schema group %s", schema).isTrue();
        assertThat(examples.get("cases").size()).isPositive();
        for (JsonNode example : examples.get("cases")) {
          JsonNode value = example.get("value");
          if (example.get("valid").booleanValue()) {
            assertThat(AccessContractParser.parse(schema, value))
                .as("%s: %s: %s", file, schema, example.get("id").textValue())
                .isNotNull();
          } else {
            assertThatThrownBy(() -> AccessContractParser.parse(schema, value))
                .as("%s: %s: %s", file, schema, example.get("id").textValue())
                .isInstanceOf(InvalidRequestException.class);
          }
          consumed++;
        }
      }
    }
    assertThat(covered).containsExactlyInAnyOrderElementsOf(SCHEMAS);
    assertThat(consumed).isPositive();
  }

  @Test
  void returnsTypedObservationsWithoutManufacturingTrustedAuthority() throws IOException {
    JsonNode value = fixtureCase("SynchronizationBootstrap");
    AccessWireContracts.Bootstrap parsed =
        (AccessWireContracts.Bootstrap)
            AccessContractParser.parse("SynchronizationBootstrap", value);
    assertThat(parsed.serverInstanceId().value())
        .isEqualTo(value.get("serverInstanceId").textValue());
    assertThat(parsed.identity()).isInstanceOf(AccessWireContracts.ConfiguredIdentity.class);
    assertThat(parsed.boundary()).isInstanceOf(AccessWireContracts.G2Boundary.class);
    assertThat(parsed.toString()).doesNotContain(parsed.expectation());
  }

  private static JsonNode fixtureCase(String schema) throws IOException {
    for (JsonNode group : fixture("access-contracts.json").get("groups")) {
      if ((schema + ".json").equals(group.get("schema").textValue())) {
        return group.get("cases").get(0).get("value");
      }
    }
    throw new AssertionError("Missing access fixture schema.");
  }

  private static JsonNode fixture(String name) throws IOException {
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
      assertThat(stream).as("fixture %s", name).isNotNull();
      return JSON.readTree(stream);
    }
  }
}
