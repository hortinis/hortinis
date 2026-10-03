import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { contractsRoot, readYamlFile } from "./tools.mjs";

const fixtureDirectory = resolve(contractsRoot, "sync/fixtures");
const readFixture = (name) => JSON.parse(readFileSync(resolve(fixtureDirectory, name), "utf8"));
const localOnly = new Set([
  "LocalDataSetId",
  "ServerOrigin",
  "LocalServerBinding",
  "LocalExchangeState",
]);

function validators() {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    validateFormats: true,
  });
  addFormats(ajv);
  ajv.addKeyword("discriminator");
  for (const file of readdirSync(resolve(contractsRoot, "schemas"))) {
    if (file.endsWith(".json")) {
      ajv.addSchema(JSON.parse(readFileSync(resolve(contractsRoot, "schemas", file), "utf8")));
    }
  }
  const openApi = readYamlFile(resolve(contractsRoot, "openapi/openapi.yaml"));
  const definitions = JSON.parse(
    JSON.stringify(openApi.components.schemas).replaceAll("#/components/schemas/", "#/$defs/"),
  );
  ajv.addSchema({ $id: "access-openapi", $defs: definitions });
  return { ajv, definitions };
}

test("every S2 fixture is checked against standalone and reachable OpenAPI schemas", () => {
  const { ajv, definitions } = validators();
  const manifest = readFixture("capabilities.json");
  const declarations = manifest.fixtures.filter(({ suite }) => suite === "access");
  assert.ok(declarations.length > 0);
  const covered = new Set();
  for (const declaration of declarations) {
    assert.equal(declaration.capability, "access-contract-parsing");
    assert.equal(declaration.status, "implemented");
    assert.deepEqual(declaration.consumers, ["contract", "typescript", "java"]);
    const fixture = readFixture(declaration.file);
    assert.ok(fixture.groups.length > 0);
    for (const group of fixture.groups) {
      const name = group.schema.replace(/\.json$/, "");
      assert.ok(!covered.has(name), `${name}: schema group is unique`);
      covered.add(name);
      const standalone = ajv.getSchema(group.schema);
      assert.ok(standalone, `${name}: standalone schema exists`);
      const schemas = [standalone];
      if (!localOnly.has(name)) {
        assert.ok(definitions[`Models.${name}`], `${name}: public wire schema exists`);
        schemas.push(ajv.getSchema(`access-openapi#/$defs/Models.${name}`));
      } else {
        assert.equal(
          definitions[`Models.${name}`],
          undefined,
          `${name}: local metadata is not an HTTP DTO`,
        );
      }
      assert.ok(group.cases.some(({ valid }) => valid));
      assert.ok(group.cases.some(({ valid }) => !valid));
      assert.equal(new Set(group.cases.map(({ id }) => id)).size, group.cases.length);
      for (const validate of schemas) {
        assert.ok(validate);
        for (const example of group.cases) {
          assert.equal(
            validate(example.value),
            example.valid,
            `${declaration.file}: ${name}: ${example.id}: ${JSON.stringify(validate.errors)}`,
          );
        }
      }
    }
  }
  assert.equal(covered.size, 18);
});

test("access operations separate public discovery from protected identity and declare no-store", () => {
  const document = readYamlFile(resolve(contractsRoot, "openapi/openapi.yaml"));
  const discovery = document.paths["/api/v1/server/capabilities"].get;
  assert.equal(
    discovery.responses["200"].content["application/json"].schema.$ref,
    "#/components/schemas/Models.ServerCapabilities",
  );
  assert.deepEqual(Object.keys(discovery.responses).sort(), ["200", "503"]);
  const bootstrap = document.paths["/api/v1/sync/bootstrap"].get;
  assert.deepEqual(Object.keys(bootstrap.responses).sort(), ["200", "401", "403", "503"]);
  const confirm = document.paths["/api/v1/sync/bindings/confirm"].post;
  assert.equal(
    confirm.requestBody.content["application/json"].schema.$ref,
    "#/components/schemas/Models.ConfirmEmptyBindingRequest",
  );
  assert.deepEqual(Object.keys(confirm.responses).sort(), [
    "200",
    "400",
    "401",
    "403",
    "409",
    "503",
  ]);
  for (const [path, item] of Object.entries(document.paths)) {
    assert.ok(!/scope|account|token/i.test(path));
    for (const operation of Object.values(item)) {
      for (const response of Object.values(operation.responses)) {
        assert.deepEqual(response.headers["Cache-Control"].schema.enum, ["no-store"]);
        assert.equal(response.headers["Cache-Control"].required, true);
      }
      for (const parameter of operation.parameters ?? []) {
        assert.ok(!/scope|account|token/i.test(parameter.name));
      }
      if (
        path.startsWith("/api/v1/sync/") &&
        !["/api/v1/sync/bootstrap", "/api/v1/sync/bindings/confirm"].includes(path)
      ) {
        const expectation = operation.parameters.find(({ name }) => name === "Sync-Expectation");
        assert.equal(expectation.in, "header");
        assert.equal(expectation.required, false);
      }
    }
  }
});
